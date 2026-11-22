package com.example

import android.app.Application
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras

/**
 * Process-scoped Application host implementing [ViewModelStoreOwner] and
 * [HasDefaultViewModelProviderFactory].
 *
 * By anchoring the shared [com.example.viewmodel.RecorderViewModel] to the Application's
 * [ViewModelStore] rather than [MainActivity]'s transient task lifecycle, swiping the app away
 * from Android's Recent Apps multitasking menu during an active or paused recording never
 * invokes `onCleared()`, never tears down [com.example.audio.AudioStudioEngine], and never
 * detaches notification or Dynamic Island action handlers.
 *
 * Implementing [HasDefaultViewModelProviderFactory] ensures Compose's `viewModel()` resolves
 * [ViewModelProvider.AndroidViewModelFactory] with [ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
 * instead of falling back to `NewInstanceFactory` (which would crash trying to invoke a zero-arg
 * constructor on [com.example.viewmodel.RecorderViewModel]).
 */
class AudioRecorderApplication : Application(), ViewModelStoreOwner, HasDefaultViewModelProviderFactory {

    private val appViewModelStore: ViewModelStore by lazy { ViewModelStore() }

    override val viewModelStore: ViewModelStore
        get() = appViewModelStore

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory by lazy {
        ViewModelProvider.AndroidViewModelFactory.getInstance(this)
    }

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras().apply {
            set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, this@AudioRecorderApplication)
        }
}
