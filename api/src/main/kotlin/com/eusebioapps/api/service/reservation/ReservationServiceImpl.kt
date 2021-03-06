package com.eusebioapps.api.service.reservation

import com.eusebioapps.api.model.Person
import com.eusebioapps.api.model.Reservation
import com.eusebioapps.api.model.enum.EventStatus
import com.eusebioapps.api.model.exception.BusinessRuleException
import com.eusebioapps.api.repository.EventRepository
import com.eusebioapps.api.repository.PersonRepository
import com.eusebioapps.api.repository.ReservationRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.*
import java.time.format.DateTimeFormatter

@Service
class ReservationServiceImpl(
    private val eventRepository: EventRepository,
    private val personRepository: PersonRepository,
    private val reservationRepository: ReservationRepository,
    // configuration
    @Value("\${app.age.min:15}") val ageMin: Int,
    @Value("\${app.age.max:65}") val ageMax: Int,
    @Value("\${app.attendance.max.attendee:250}") val maxAttendeeAttendance: Int,
    @Value("\${app.attendance.max.volunteer:250}") val maxVolunteerAttendance: Int,
    @Value("\${app.check.age:false}") val checkAge: Boolean,
    @Value("\${app.check.vaccinated:false}") val checkVaccinated: Boolean
) : ReservationService {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun reserve(
        mobileNo: String,
        email: String,
        firstName: String,
        lastName: String,
        birthday: String,
        fullAddress: String,
        city: String,
        vaccinated: Boolean,
        volunteer: Boolean
    ) : Reservation {
        logger.debug("reserve: (start) " +
                "[mobileNo={},email={},firstName={},lastName={}," +
                "birthday={},fullAddress={},city={},vaccinated={},volunteer={}]",
            mobileNo, email, firstName, lastName, birthday, fullAddress, city, vaccinated, volunteer)

        val dtf: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val convertedBirthday: LocalDate = LocalDate.parse(birthday, dtf)

        // Validate event
        val currentEvent = eventRepository.findTop1ByOrderByEventDateTimeDesc()
            ?: throw BusinessRuleException("There is no event with on-going registration. Please create a new event.")
        val reservationList = reservationRepository.findByEvent(currentEvent)

        // Add or update person
        val dbPerson: Person? = personRepository.findByMobileNo(mobileNo)
        val person = if(dbPerson != null) {
            // Update details of the person
            val updatedPerson =
                dbPerson.copy(
                    email = email,
                    firstName = firstName,
                    lastName =  lastName,
                    birthday = convertedBirthday,
                    fullAddress = fullAddress,
                    city = city,
                    vaccinated = vaccinated
                )
            logger.debug("reserve: (person updated) [{}]", updatedPerson)
            personRepository.save(updatedPerson)
        } else {
            val newPerson =
                Person(
                    null,
                    mobileNo,
                    email,
                    firstName,
                    lastName,
                    convertedBirthday,
                    fullAddress,
                    city,
                    vaccinated
                )
            logger.debug("reserve: (person saved) [{}]", newPerson)
            personRepository.save(newPerson)
        }

        // Validate birthday
        if (this.checkAge) {
            val age = Period.between(convertedBirthday, LocalDate.now()).years
            logger.debug("reserve: (validating age) [birthday={},age={}]", convertedBirthday, age)
            if (age < this.ageMin || age > this.ageMax) {
                throw BusinessRuleException("Sorry, only people ages ${this.ageMin} to ${this.ageMax} are allowed.")
            }
        }

        // Validate vaccination
        if (this.checkVaccinated) {
            logger.debug("reserve: (validating vaccination) [vaccinated={}]", vaccinated)
            if (!vaccinated) {
                throw BusinessRuleException("Sorry, only vaccinated individuals are allowed.")
            }
        }

        // Validate if person is already reserved
        var existingReservation = reservationRepository.findByPersonAndEvent(person, currentEvent)
        if(existingReservation != null) {
            logger.debug("reserve: (done - has existing) [person={},event={}]", person, currentEvent)
            existingReservation = reservationRepository.save(existingReservation.copy(volunteer = volunteer))
            return existingReservation
        }

        // Validate if max attendance
        logger.debug("reserve: (validating current event) [size={},event={}]", reservationList.size, currentEvent)
        if (volunteer) {
            if (reservationList.count { it.volunteer } >= this.maxVolunteerAttendance) {
                throw BusinessRuleException("Volunteer attendance limit reached. Only ${this.maxVolunteerAttendance} people are allowed.")
            }
        } else {
            if(reservationList.count { !it.volunteer } >= this.maxAttendeeAttendance) {
                throw BusinessRuleException("Event attendance limit reached. Only ${this.maxAttendeeAttendance} people are allowed.")
            }
        }

        // Save reservation
        val currentTime = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var newReservation = Reservation(null, person, currentEvent, volunteer, currentTime, null)
        newReservation = reservationRepository.save(newReservation)
        logger.info("reserve: (done - saved) {}", newReservation)

        // If reservation reached max attendance after saving, close event
        if(reservationList.size + 1 >= this.maxAttendeeAttendance + this.maxVolunteerAttendance) {
            val savedEvent = eventRepository.save(currentEvent.copy(status = EventStatus.CLOSED))
            logger.info("reserve: (max attendance) Closing event. [size={}, event={}]", reservationList.size + 1, savedEvent)
        }

        return newReservation
    }

    override fun scan(id: String) : Reservation {
        val currentTime = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val dbReservation = reservationRepository.findById(id)
        if(dbReservation.isEmpty) {
            throw BusinessRuleException("Reservation does not exist")
        }
        var updatedReservation: Reservation = dbReservation.get().copy(scannedDateTime = currentTime)
        updatedReservation = reservationRepository.save(updatedReservation)
        logger.info("scan: {}", updatedReservation)
        return updatedReservation
    }

}