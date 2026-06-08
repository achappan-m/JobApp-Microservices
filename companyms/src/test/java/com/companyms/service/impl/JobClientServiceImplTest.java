package com.companyms.service.impl;

import com.companyms.bean.JobSummary;
import com.companyms.client.JobClient;
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
class JobClientServiceImplTest {

    @Mock
    private JobClient jobClient;

    @InjectMocks
    private JobClientServiceImpl jobClientService;

    // ── 1. Happy path ──────────────────────────────────────────────────────────

    @Test
    void getJobSummary_delegatesToClient_returnsClientList() {
        Long companyId = 42L;
        List<JobSummary> expected = List.of(
            new JobSummary(1L, "Engineer", "Backend role", "London", "60000", "90000")
        );
        when(jobClient.getJobsByCompany(companyId)).thenReturn(expected);

        List<JobSummary> result = jobClientService.getJobSummary(companyId);

        assertThat(result).isSameAs(expected);
        verify(jobClient).getJobsByCompany(companyId);
    }

    // ── 2. Read fallback: silent degradation ───────────────────────────────────
    // Fallback body is called directly; Resilience4j AOP is not active in plain
    // unit tests. This asserts what the circuit breaker will invoke on open/error.

    @Test
    void getJobFallback_returnsEmptyList_silentDegradationOnReads() {
        List<JobSummary> result = jobClientService.getJobFallback(42L, new RuntimeException("jobms down"));

        assertThat(result).isEmpty();
        verifyNoInteractions(jobClient);
    }

    // ── 3. Delete fallback: loud failure ───────────────────────────────────────

    @Test
    void deleteJobFallback_throwsRuntimeException_loudFailureOnDeletes() {
        assertThatThrownBy(() -> jobClientService.deleteJobFallback(42L, new RuntimeException("jobms down")))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Job service is currently unavailable");

        verifyNoInteractions(jobClient);
    }
}
