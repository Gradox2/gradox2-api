package com.example.gradox2.persistence.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.gradox2.persistence.entities.Proposal;
import com.example.gradox2.persistence.entities.User;
import com.example.gradox2.persistence.entities.Vote;
import java.util.List;
import java.util.Optional;


public interface VoteRepository extends JpaRepository<Vote, Long> {
    /**
     * WAVE2 S4: el conteo de quórum recorre los votos y lee votante, rol y grado. Sin este fetch graph
     * cada voto disparaba su propia consulta al votante (N+1).
     */
    @EntityGraph(attributePaths = { "voter", "voter.degree" })
    List<Vote> findByProposalId(Long proposalId);
    List<Vote> findByVoterId(Long voterId);
    Optional<Vote> findByVoterAndProposal(User voter, Proposal proposal);
    long countByProposalAndInFavor(Proposal proposal, boolean b);

}
