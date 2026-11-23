package com.example.gradox2;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.example.gradox2.persistence.entities.ConfigProposal;
import com.example.gradox2.persistence.entities.Course;
import com.example.gradox2.persistence.entities.Degree;
import com.example.gradox2.persistence.entities.FileProposal;
import com.example.gradox2.persistence.entities.Proposal;
import com.example.gradox2.persistence.entities.Subject;
import com.example.gradox2.persistence.entities.TempFile;
import com.example.gradox2.persistence.entities.User;
import com.example.gradox2.persistence.entities.enums.ActionType;
import com.example.gradox2.persistence.entities.enums.ProposalStatus;
import com.example.gradox2.persistence.entities.enums.UserRole;
import com.example.gradox2.persistence.repository.AuditRecordRepository;
import com.example.gradox2.persistence.repository.DegreeRepository;
import com.example.gradox2.persistence.repository.ProposalRepository;
import com.example.gradox2.persistence.repository.UserRepository;
import com.example.gradox2.service.interfaces.IVoteService;
import com.example.gradox2.support.TestDatabaseCleaner;
import com.example.gradox2.support.TestDegrees;

/**
 * WAVE2 S5: el cierre automático de propuestas vencidas se procesa por páginas y es idempotente.
 *
 * <p>Antes traía todas las vencidas de una sola vez y, si el tick se repetía (o dos instancias
 * corrían a la vez), volvía a auditar y a descontar reputación sobre propuestas ya cerradas.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchedulerExpirationTest {

    private static final String PASSWORD = "SecurePass1!";

    @Autowired
    private IVoteService voteService;

    @Autowired
    private ProposalRepository proposalRepository;

    @Autowired
    private com.example.gradox2.persistence.repository.CourseRepository courseRepository;

    @Autowired
    private com.example.gradox2.persistence.repository.SubjectRepository subjectRepository;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DegreeRepository degreeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

    private User proposer;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        Degree degree = TestDegrees.informatica(degreeRepository);
        proposer = userRepository.save(User.builder()
                .username("expirationproposer")
                .email("expirationproposer@rai.usc.es")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(UserRole.MASTER)
                .degree(degree)
                .enabled(true)
                .build());
    }

    @Test
    void closesExpiredProposalsOnceAndIgnoresClosedOnes() {
        List<Proposal> expired = List.of(
                expiredProposal(ProposalStatus.PENDING),
                expiredProposal(ProposalStatus.PENDING),
                expiredProposal(ProposalStatus.PENDING));
        Proposal future = expiredProposal(ProposalStatus.PENDING);
        future.setEndsAt(Instant.now().plusSeconds(3600));
        proposalRepository.save(future);
        Proposal alreadyClosed = expiredProposal(ProposalStatus.REJECTED);

        long auditsBefore = auditRecordRepository.count();

        assertThat(voteService.closeExpiredProposals())
                .as("cierra solo las vencidas que siguen pendientes")
                .isEqualTo(3);

        expired.forEach(proposal -> assertThat(proposalRepository.findById(proposal.getId()).orElseThrow()
                .getStatus()).isEqualTo(ProposalStatus.REJECTED));
        assertThat(proposalRepository.findById(future.getId()).orElseThrow().getStatus())
                .as("una propuesta que aún no venció no se toca")
                .isEqualTo(ProposalStatus.PENDING);
        assertThat(auditRecordRepository.count() - auditsBefore)
                .as("cada cierre audita una sola vez")
                .isEqualTo(3);

        long auditsAfterFirstRun = auditRecordRepository.count();
        assertThat(voteService.closeExpiredProposals())
                .as("una segunda pasada no encuentra nada que cerrar")
                .isZero();
        assertThat(auditRecordRepository.count())
                .as("un tick repetido no vuelve a auditar propuestas ya cerradas")
                .isEqualTo(auditsAfterFirstRun);
        assertThat(proposalRepository.findById(alreadyClosed.getId()).orElseThrow().getStatus())
                .isEqualTo(ProposalStatus.REJECTED);
    }

    @Test
    void closesMoreProposalsThanOnePageInASingleRun() {
        for (int i = 0; i < 120; i++) {
            expiredProposal(ProposalStatus.PENDING);
        }

        assertThat(voteService.closeExpiredProposals())
                .as("120 vencidas con páginas de 100: el tick no se queda en la primera")
                .isEqualTo(120);

        assertThat(proposalRepository.findAll())
                .filteredOn(proposal -> proposal.getStatus() == ProposalStatus.PENDING)
                .as("ninguna propuesta vencida queda pendiente tras el tick")
                .isEmpty();
    }

    @Test
    void repeatedTickDoesNotDeductReputationTwice() {
        User uploader = userRepository.save(User.builder()
                .username("expirationuploader")
                .email("expirationuploader@rai.usc.es")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(UserRole.USER)
                .degree(TestDegrees.informatica(degreeRepository))
                .reputation(20.0)
                .enabled(true)
                .build());
        FileProposal proposal = expiredFileProposal(ProposalStatus.PENDING, uploader);
        long auditsBefore = auditRecordRepository.count();

        assertThat(voteService.closeExpiredProposals()).isEqualTo(1);

        assertThat(userRepository.findById(uploader.getId()).orElseThrow().getReputation())
                .as("una subida rechazada descuenta 5 de reputación")
                .isEqualTo(15.0);
        assertThat(auditRecordRepository.count() - auditsBefore).isEqualTo(1);

        assertThat(voteService.closeExpiredProposals())
                .as("la propuesta ya está cerrada")
                .isZero();

        assertThat(userRepository.findById(uploader.getId()).orElseThrow().getReputation())
                .as("un tick repetido no vuelve a descontar reputación")
                .isEqualTo(15.0);
        assertThat(auditRecordRepository.count() - auditsBefore)
                .as("un tick repetido no vuelve a auditar")
                .isEqualTo(1);
        assertThat(proposalRepository.findById(proposal.getId()).orElseThrow().getStatus())
                .isEqualTo(ProposalStatus.REJECTED);
    }

    private FileProposal expiredFileProposal(ProposalStatus status, User uploader) {
        Subject subject = subjectRepository.save(Subject.builder()
                .code("BD1")
                .name("Bases de Datos I")
                .course(courseRepository.save(Course.builder()
                        .code("1")
                        .name("Primer curso")
                        .degree(TestDegrees.informatica(degreeRepository))
                        .build()))
                .build());

        FileProposal proposal = new FileProposal();
        proposal.setProposer(uploader);
        proposal.setActionType(ActionType.UPLOAD);
        proposal.setQuorumRequired(3);
        proposal.setApprovalThreshold(0.5);
        proposal.setStatus(status);
        proposal.setEndsAt(Instant.now().minusSeconds(60));
        proposal.setTempFile(TempFile.builder()
                .title("Apuntes")
                .type(com.example.gradox2.persistence.entities.enums.FileType.APUNTES)
                .objectKey("files/temp.pdf")
                .sizeBytes(10L)
                .uploader(uploader)
                .subject(subject)
                .visibilityLevel(com.example.gradox2.persistence.entities.enums.FileVisibility.PUBLIC)
                .build());
        return proposalRepository.save(proposal);
    }

    private Proposal expiredProposal(ProposalStatus status) {
        ConfigProposal proposal = new ConfigProposal();
        proposal.setProposer(proposer);
        proposal.setActionType(ActionType.POLICY_CHANGE);
        proposal.setQuorumRequired(3);
        proposal.setApprovalThreshold(0.5);
        proposal.setProposedQuorumRequired(5);
        proposal.setStatus(status);
        proposal.setEndsAt(Instant.now().minusSeconds(60));
        return proposalRepository.save(proposal);
    }
}
