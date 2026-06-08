package com.companyms.service.impl;

import com.companyms.bean.ReviewSummary;
import com.companyms.client.ReviewClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewClientServiceImplTest {

    @Mock
    private ReviewClient reviewClient;

    @InjectMocks
    private ReviewClientServiceImpl reviewClientService;

    // ── 1. Happy path ──────────────────────────────────────────────────────────

    @Test
    void getReviewSummary_delegatesToClient_returnsClientList() {
        Long companyId = 42L;
        List<ReviewSummary> expected = List.of(
            new ReviewSummary(1L, "Great place", "Good culture and pay", 4.5)
        );
        when(reviewClient.getReviewsByCompany(companyId)).thenReturn(expected);

        List<ReviewSummary> result = reviewClientService.getReviewSummary(companyId);

        assertThat(result).isSameAs(expected);
        verify(reviewClient).getReviewsByCompany(companyId);
    }

    // ── 2. Read fallback: silent degradation ───────────────────────────────────
    // Fallback body is called directly; Resilience4j AOP is not active in plain
    // unit tests. This asserts what the circuit breaker will invoke on open/error.

    @Test
    void getReviewFallback_returnsEmptyList_silentDegradationOnReads() {
        List<ReviewSummary> result = reviewClientService.getReviewFallback(42L, new RuntimeException("reviewms down"));

        assertThat(result).isEmpty();
        verifyNoInteractions(reviewClient);
    }

    // ── 3. Delete fallback: loud failure ───────────────────────────────────────

    @Test
    void deleteReviewFallback_throwsRuntimeException_loudFailureOnDeletes() {
        assertThatThrownBy(() -> reviewClientService.deleteReviewFallback(42L, new RuntimeException("reviewms down")))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Review service is currently unavailable");

        verifyNoInteractions(reviewClient);
    }
}
