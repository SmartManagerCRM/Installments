package com.smartmanager.installments.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Time source. Overdue detection uses the DEVICE LOCAL DATE; there is no background/online time service. */
interface AppClock {
    fun nowMillis(): Long
    fun zone(): ZoneId
    fun today(): LocalDate = Instant.ofEpochMilli(nowMillis()).atZone(zone()).toLocalDate()
}

class SystemAppClock : AppClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/** Observable "today" so the dashboard flips at midnight / after a date change without restarting the app. */
class TodayTicker(private val clock: AppClock) {
    private val _today = MutableStateFlow(clock.today())
    val today: StateFlow<LocalDate> = _today
    fun refresh() {
        val t = clock.today()
        if (t != _today.value) _today.value = t
    }
}
