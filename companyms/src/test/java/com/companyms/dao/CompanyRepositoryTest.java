package com.companyms.dao;

import com.companyms.entity.CompanyEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class CompanyRepositoryTest {

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void updateRatingAtomically_duplicateDeleteDelivery_doesNotDriveCountOrSumNegative() {
        // Arrange: company with exactly one review
        CompanyEntity company = entityManager.persistAndFlush(
            new CompanyEntity(null, "Acme", "Test company", 4.5, 1, 4.5)
        );
        Long id = company.getId();
        entityManager.clear();

        // First delivery — expected correct behaviour
        companyRepository.updateRatingAtomically(id, -4.5, -1);
        entityManager.clear(); // must clear: @Modifying does not evict the first-level cache

        CompanyEntity afterFirst = companyRepository.findById(id).orElseThrow();
        assertThat(afterFirst.getReviewCount()).isZero();
        assertThat(afterFirst.getRatingSum()).isEqualTo(0.0);
        assertThat(afterFirst.getAverageRating()).isEqualTo(0.0);
        entityManager.clear();

        // Second delivery (duplicate — RabbitMQ at-least-once redelivery)
        // Without a floor guard in the JPQL, this drives reviewCount to -1
        // and ratingSum negative. The assertions below FAIL NOW.
        companyRepository.updateRatingAtomically(id, -4.5, -1);
        entityManager.clear();

        CompanyEntity afterSecond = companyRepository.findById(id).orElseThrow();
        assertThat(afterSecond.getReviewCount())
            .as("reviewCount must not go negative on duplicate delete delivery")
            .isGreaterThanOrEqualTo(0);
        assertThat(afterSecond.getRatingSum())
            .as("ratingSum must not go negative on duplicate delete delivery")
            .isGreaterThanOrEqualTo(0.0);
        assertThat(afterSecond.getAverageRating())
            .as("averageRating must not go negative on duplicate delete delivery")
            .isGreaterThanOrEqualTo(0.0);
    }
}
