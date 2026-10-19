package io.github.sanskar121543.reconciliation.run;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RunRepository extends JpaRepository<Run, Long> {

    /** Imports and the run's completion take this lock, so they never interleave for one run. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Run r WHERE r.id = :id")
    Optional<Run> lockById(long id);

    List<Run> findAllByOrderByIdDesc(Limit limit);
}
