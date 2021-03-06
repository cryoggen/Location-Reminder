package com.cryoggen.locationreminder.statistics

import android.app.Application
import androidx.lifecycle.*
import com.cryoggen.locationreminder.data.Result
import com.cryoggen.locationreminder.data.Result.Error
import com.cryoggen.locationreminder.data.Result.Success
import com.cryoggen.locationreminder.data.Reminder
import com.cryoggen.locationreminder.data.source.RemindersRepository
import kotlinx.coroutines.launch


class StatisticsViewModel(application: Application) : AndroidViewModel(application) {

    private val remindersRepository = RemindersRepository.getRepository(application)

    private val reminders: LiveData<Result<List<Reminder>>> = remindersRepository.observeReminders()
    private val _dataLoading = MutableLiveData(false)
    private val stats: LiveData<StatsResult?> = reminders.map {
        if (it is Success) {
            getActiveAndCompletedStats(it.data)
        } else {
            null
        }
    }

    val activeRemindersPercent = stats.map {
        it?.activeRemindersPercent ?: 0f
    }
    val completedRemindersPercent: LiveData<Float> =
        stats.map { it?.completedRemindersPercent ?: 0f }
    val dataLoading: LiveData<Boolean> = _dataLoading
    val error: LiveData<Boolean> = reminders.map { it is Error }
    val empty: LiveData<Boolean> = reminders.map { (it as? Success)?.data.isNullOrEmpty() }

    fun refresh() {
        _dataLoading.value = true
        viewModelScope.launch {
            remindersRepository.observeReminders()
            _dataLoading.value = false
        }
    }
}
