package com.companyms.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.companyms.dao.CompanyRepository;
import com.companyms.eventDTO.ReviewCreatedEvent;
import com.companyms.eventDTO.ReviewDeletedEvent;
import com.companyms.eventDTO.ReviewUpdatedEvent;
import com.companyms.mapper.CompanyMapper;
import com.companyms.messaging.CompanyEventPublisher;
import com.companyms.service.JobClientService;
import com.companyms.service.ReviewClientService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyServiceImplTest {

    @Mock private CompanyRepository companyRepository;
    @Mock private CompanyMapper companyMapper;
    @Mock private CompanyEventPublisher companyEventPublisher;
    @Mock private JobClientService jobClientService;
    @Mock private ReviewClientService reviewClientService;

    @InjectMocks
    private CompanyServiceImpl companyService;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(CompanyServiceImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(CompanyServiceImpl.class)).detachAppender(logAppender);
    }

    // ── review.created ─────────────────────────────────────────────────────────

    @Test
    void updateRatingOnCreate_missingCompany_logsWarnAndAcks() {
        when(companyRepository.updateRatingAtomically(42L, 4.5, 1)).thenReturn(0);

        assertDoesNotThrow(() ->
            companyService.updateCompanyRatingOnCreate(new ReviewCreatedEvent(1L, 4.5, 42L)));

        assertThat(logAppender.list)
            .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("42"));
    }

    // ── review.updated ─────────────────────────────────────────────────────────

    @Test
    void updateRatingOnUpdate_missingCompany_logsWarnAndAcks() {
        // oldRating=3.0, newRating=4.0 → deltaSum passed to repo = 4.0 - 3.0 = 1.0
        when(companyRepository.updateRatingAtomically(42L, 1.0, 0)).thenReturn(0);

        assertDoesNotThrow(() ->
            companyService.updateCompanyRatingOnUpdate(new ReviewUpdatedEvent(1L, 3.0, 4.0, 42L)));

        assertThat(logAppender.list)
            .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("42"));
    }

    // ── review.deleted ─────────────────────────────────────────────────────────

    @Test
    void updateRatingOnDelete_missingCompany_logsWarnAndAcks() {
        when(companyRepository.updateRatingAtomically(42L, -4.5, -1)).thenReturn(0);

        assertDoesNotThrow(() ->
            companyService.updateCompanyRatingOnDelete(new ReviewDeletedEvent(1L, 4.5, 42L)));

        assertThat(logAppender.list)
            .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("42"));
    }
}
