package com.hakkiiler.voiceassistant.domain.model

import com.hakkiiler.voiceassistant.domain.parser.DateTimeSpec
import java.time.LocalDate

enum class SettingsPanel { GENERAL, WIFI, BLUETOOTH, DISPLAY, LANGUAGE }

enum class SmallTalk { GREETING, HOW_ARE_YOU, WHO_ARE_YOU, WHAT_CAN_YOU_DO }

enum class RangeKind { DAY, THIS_WEEK, NEXT_WEEK }

data class EventRange(val start: LocalDate, val endInclusive: LocalDate, val kind: RangeKind)

/** Результат разбора фразы — языконезависимое намерение. */
sealed interface VoiceCommand {
    data object Repeat : VoiceCommand
    data object Thanks : VoiceCommand
    data object Stop : VoiceCommand
    data object ShutdownAssistant : VoiceCommand
    data object PowerMenu : VoiceCommand
    data object ClearMemory : VoiceCommand

    data object MusicPlay : VoiceCommand
    data object MusicPause : VoiceCommand
    data object MusicResume : VoiceCommand
    data object MusicNext : VoiceCommand
    data object MusicPrevious : VoiceCommand

    data class VolumeSet(val percent: Int) : VoiceCommand
    data object VolumeUp : VoiceCommand
    data object VolumeDown : VoiceCommand

    data class Flashlight(val on: Boolean) : VoiceCommand
    data object Battery : VoiceCommand
    data class OpenSettings(val panel: SettingsPanel) : VoiceCommand

    data object TellTime : VoiceCommand
    data object TellDate : VoiceCommand

    data class Math(val result: com.hakkiiler.voiceassistant.domain.parser.MathResult) : VoiceCommand

    data class CreateEvent(val spec: DateTimeSpec?, val title: String?) : VoiceCommand
    data class QueryEvents(val range: EventRange) : VoiceCommand

    data class OpenApp(val name: String) : VoiceCommand
    /** «нарисуй …» / "draw …"; prompt == null — просто открыть экран рисования. */
    data class Draw(val prompt: String?) : VoiceCommand
    data class Chat(val kind: SmallTalk) : VoiceCommand
    data class Unknown(val text: String) : VoiceCommand
}
