package com.eusebioapps.api.scheduler

import com.eusebioapps.api.model.Event
import com.eusebioapps.api.model.enum.EventStatus
import com.eusebioapps.api.model.exception.BusinessRuleException
import com.eusebioapps.api.repository.EventRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId

import java.time.Instant

@Component
class EventScheduler(private val eventRepository: EventRepository) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 2 ? * SUN") // UTC SUN 2:00 AM -> GMT+8 SUN 10:00 AM
    fun createEvent() {
        val nextSunday = LocalDateTime.now().plusDays(7)
        val instant: Instant = nextSunday.atZone(ZoneId.systemDefault()).toInstant()
        val epochMilli = instant.toEpochMilli()

        val newEntity = Event(null,"Sunday Service " + nextSunday.year + "-" + nextSunday.month + "-" + nextSunday.dayOfMonth, epochMilli, EventStatus.OPEN)
        eventRepository.save(newEntity)
        logger.debug("createEvent: {}", newEntity)
    }

    @Scheduled(cron = "0 0 9 ? * FRI") // UTC FRI 9:00 AM -> GMT+8 FRI 5:00 PM
    fun closeEventRegistration() {
        val currentEvent = eventRepository.findTop1ByOrderByEventDateTimeDesc()
            ?: throw BusinessRuleException("There is no event with on-going registration. Please create a new event.")
        val updatedEvent: Event = currentEvent.copy(status = EventStatus.CLOSED)
        eventRepository.save(updatedEvent)
        logger.debug("closeEventRegistration: {}", updatedEvent)
    }

}