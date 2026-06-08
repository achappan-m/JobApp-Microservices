package com.companyms.dao;

import com.companyms.entity.CompanyEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the floor-clamp behaviour in updateRatingAtomically.
 * Uses @DataJpaTest — H2 is auto-configured; no running database required.
 *
 * Distinct from CompanyRepositoryTest: uses exact equality assertions (isZero)
 * rather than inequality guards, and asserts the row-count returned by each call.
 */
@DataJpaTest
class CompanyRepositoryIntegrationTest {

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void updateRatingAtomically_floorClamp_preventsNegativeValuesOnDuplicateDelete() {
        // ── Arrange ──────────────────────────────────────────────────────────────
        // One review, rating 4.5 — valid starting state.
        CompanyEntity saved = entityManager.persistAndFlush(
                new CompanyEntity(null, "Acme Corp", "A test company", 4.5, 1, 4.5)
        );
        Long id = saved.getId();
        entityManager.clear();

        // ── Act: first delivery (normal review-delete) ────────────────────────────
        // deltaSum = -4.5, deltaCount = -1  →  0 + 0 reviews → all fields floor to 0
        int firstRowCount = companyRepository.updateRatingAtomically(id, -4.5, -1);
        entityManager.clear(); // @Modifying does not evict the first-level cache

        // ── Assert: first delivery ────────────────────────────────────────────────
        assertThat(firstRowCount)
                .as("one row should be matched and updated")
                .isEqualTo(1);

        CompanyEntity afterFirst = companyRepository.findById(id).orElseThrow();
        assertThat(afterFirst.getReviewCount())
                .as("reviewCount after normal delete")
                .isZero();
        assertThat(afterFirst.getRatingSum())
                .as("ratingSum after normal delete")
                .isEqualTo(0.0);
        assertThat(afterFirst.getAverageRating())
                .as("averageRating after normal delete")
                .isEqualTo(0.0);
        entityManager.clear();

        // ── Act: second delivery (duplicate — RabbitMQ at-least-once redelivery) ──
        // Same deltas applied to already-zeroed fields. Without the floor clamp the
        // query would drive reviewCount to -1 and ratingSum to -4.5.
        int secondRowCount = companyRepository.updateRatingAtomically(id, -4.5, -1);
        entityManager.clear();

        // ── Assert: duplicate delivery ────────────────────────────────────────────
        assertThat(secondRowCount)
                .as("company row still matched even though values were clamped")
                .isEqualTo(1);

        CompanyEntity afterSecond = companyRepository.findById(id).orElseThrow();
        assertThat(afterSecond.getReviewCount())
                .as("reviewCount must stay at floor (0) on duplicate delete")
                .isZero();
        assertThat(afterSecond.getRatingSum())
                .as("ratingSum must stay at floor (0.0) on duplicate delete")
                .isEqualTo(0.0);
        assertThat(afterSecond.getAverageRating())
                .as("averageRating must stay at floor (0.0) on duplicate delete")
                .isEqualTo(0.0);
    }
}
