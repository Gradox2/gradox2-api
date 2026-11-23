package com.example.gradox2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.example.gradox2.persistence.entities.ConfigProposal;
import com.example.gradox2.persistence.entities.Course;
import com.example.gradox2.persistence.entities.Degree;
import com.example.gradox2.persistence.entities.File;
import com.example.gradox2.persistence.entities.Score;
import com.example.gradox2.persistence.entities.Subject;
import com.example.gradox2.persistence.entities.User;
import com.example.gradox2.persistence.entities.Vote;
import com.example.gradox2.persistence.entities.enums.ActionType;
import com.example.gradox2.persistence.entities.enums.FileType;
import com.example.gradox2.persistence.entities.enums.FileVisibility;
import com.example.gradox2.persistence.entities.enums.UserRole;
import com.example.gradox2.persistence.repository.CourseRepository;
import com.example.gradox2.persistence.repository.DegreeRepository;
import com.example.gradox2.persistence.repository.FileRepository;
import com.example.gradox2.persistence.repository.ProposalRepository;
import com.example.gradox2.persistence.repository.ScoreRepository;
import com.example.gradox2.persistence.repository.SubjectRepository;
import com.example.gradox2.persistence.repository.UserRepository;
import com.example.gradox2.persistence.repository.VoteRepository;
import com.example.gradox2.presentation.dto.files.FileResponse;
import com.example.gradox2.service.interfaces.IFileService;
import com.example.gradox2.support.TestDatabaseCleaner;
import com.example.gradox2.support.TestDegrees;

import jakarta.persistence.EntityManagerFactory;

/**
 * WAVE2 S4: ni el listado de archivos ni el conteo de votos pueden ejecutar una consulta por elemento.
 *
 * <p>Fija el número de sentencias preparadas con Hibernate statistics: si alguien quita el fetch graph
 * o el batch de `scores`, el test falla con el conteo real en el mensaje.
 */
@SpringBootTest
@ActiveProfiles("test")
class QueryCountRegressionTest {

    private static final String PASSWORD = "SecurePass1!";

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private IFileService fileService;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private DegreeRepository degreeRepository;

    @Autowired
    private ScoreRepository scoreRepository;

    @Autowired
    private ProposalRepository proposalRepository;

    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        cacheManager.getCache("degrees").clear();
        cacheManager.getCache("courses").clear();
        cacheManager.getCache("subjects").clear();
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    void fileListingDoesNotQueryOncePerFile() {
        Degree degree = TestDegrees.informatica(degreeRepository);
        Subject subject = createSubject(degree, "BD1");
        User uploader = createUser("uploader1", degree, UserRole.USER);

        for (int i = 0; i < 5; i++) {
            File file = createFile(uploader, subject, i);
            scoreRepository.save(Score.builder().user(uploader).file(file).score(4.0).build());
        }

        statistics.clear();
        List<FileResponse> files = fileService.getAllFiles();

        assertThat(files).hasSize(5);
        // 1 consulta del listado (con subject/curso/grado/uploader) + 1 batch para los scores.
        assertThat(statistics.getPrepareStatementCount())
                .as("listado de archivos: sentencias preparadas")
                .isLessThanOrEqualTo(4L);
    }

    @Test
    void voteTallyDoesNotQueryOncePerVote() {
        Degree degree = TestDegrees.informatica(degreeRepository);
        User proposer = createUser("proposer1", degree, UserRole.USER);
        ConfigProposal proposal = createProposal(proposer);

        for (int i = 0; i < 6; i++) {
            User voter = createUser("voter" + i, degree, i % 2 == 0 ? UserRole.MASTER : UserRole.USER);
            voteRepository.save(Vote.builder().voter(voter).proposal(proposal).inFavor(i % 2 == 0).build());
        }

        statistics.clear();
        List<Vote> votes = voteRepository.findByProposalId(proposal.getId());
        // El conteo real lee rol y grado de cada votante: sin fetch graph, esto sería 1 + 6 consultas.
        votes.forEach(vote -> {
            vote.getVoter().getRole();
            vote.getVoter().getDegree().getCode();
        });

        assertThat(votes).hasSize(6);
        assertThat(statistics.getPrepareStatementCount())
                .as("conteo de votos: sentencias preparadas")
                .isLessThanOrEqualTo(2L);
    }

    private User createUser(String username, Degree degree, UserRole role) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@rai.usc.es")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .role(role)
                .degree(degree)
                .enabled(true)
                .build());
    }

    private Subject createSubject(Degree degree, String code) {
        Course course = courseRepository.save(Course.builder()
                .code("1")
                .name("Primer curso")
                .degree(degree)
                .build());
        return subjectRepository.save(Subject.builder()
                .code(code)
                .name("Asignatura " + code)
                .course(course)
                .build());
    }

    private File createFile(User uploader, Subject subject, int index) {
        return fileRepository.save(File.builder()
                .title("Apuntes " + index)
                .description("Apuntes de prueba")
                .type(FileType.APUNTES)
                .objectKey("files/" + subject.getCode() + "-" + index + ".pdf")
                .originalFilename("apuntes" + index + ".pdf")
                .sizeBytes(10L)
                .fileHash("hash-" + index)
                .subject(subject)
                .uploader(uploader)
                .visibilityLevel(FileVisibility.PUBLIC)
                .build());
    }

    private ConfigProposal createProposal(User proposer) {
        ConfigProposal proposal = new ConfigProposal();
        proposal.setProposer(proposer);
        proposal.setActionType(ActionType.POLICY_CHANGE);
        proposal.setQuorumRequired(3);
        proposal.setApprovalThreshold(0.5);
        proposal.setProposedQuorumRequired(5);
        return proposalRepository.save(proposal);
    }
}
