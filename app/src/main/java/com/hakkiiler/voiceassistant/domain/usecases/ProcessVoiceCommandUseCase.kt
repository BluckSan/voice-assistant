package com.hakkiiler.voiceassistant.domain.usecases

import com.hakkiiler.voiceassistant.domain.image.ContentSafety
import com.hakkiiler.voiceassistant.domain.model.CustomCommand
import com.hakkiiler.voiceassistant.domain.model.Event
import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.model.SmallTalk
import com.hakkiiler.voiceassistant.domain.model.VoiceCommand
import com.hakkiiler.voiceassistant.domain.parser.AppMatcher
import com.hakkiiler.voiceassistant.domain.parser.CommandParser
import com.hakkiiler.voiceassistant.domain.parser.DateTimeParser
import com.hakkiiler.voiceassistant.domain.parser.DateTimeSpec
import com.hakkiiler.voiceassistant.domain.parser.Keywords
import com.hakkiiler.voiceassistant.domain.parser.MathResult
import com.hakkiiler.voiceassistant.domain.parser.NumberWords
import com.hakkiiler.voiceassistant.domain.parser.Text
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Ответ ассистента. */
data class Reply(
    val text: String,
    val language: Language,
    /** Продолжать слушать без активатора (идёт диалог, например создание события). */
    val keepListening: Boolean = false,
    /** Пользователь попросил выключить ассистента. */
    val shutdown: Boolean = false,
)

/** Пошаговое создание события голосом. */
data class EventSession(
    val step: Step,
    val date: LocalDate? = null,
    val dateTime: LocalDateTime? = null,
    val title: String? = null,
) {
    enum class Step { AWAITING_WHEN, AWAITING_TIME, AWAITING_TITLE, AWAITING_REMINDER }
}

/**
 * Выполняет распознанную фразу: разбирает в [VoiceCommand], вызывает действие, формирует ответ на языке пользователя.
 */
class ProcessVoiceCommandUseCase(
    private val device: DeviceController,
    private val events: EventStore,
    private val commands: CommandStore,
    private val reminders: ReminderScheduler,
    private val drawLauncher: DrawLauncher? = null,
    private val safety: ContentSafety = ContentSafety.default,
    private val parser: CommandParser = CommandParser(),
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    private var session: EventSession? = null
    private var lastCommand: Pair<String, Language>? = null

    val inDialog: Boolean get() = session != null

    suspend fun execute(text: String, lang: Language): Reply {
        session?.let { return continueEvent(it, text, lang) }

        // 1. пользовательские команды — точное совпадение фразы
        val normalized = Text.normalize(text)
        commands.currentCommands().firstOrNull { c -> c.triggers.any { Text.normalize(it) == normalized } }
            ?.let { return remember(text, lang, openAlias(it, lang)) }

        val cmd = parser.parse(text, lang)
        if (cmd == VoiceCommand.Repeat) {
            val last = lastCommand ?: return Reply(Replies(lang).nothingToRepeat, lang)
            return execute(last.first, last.second)
        }
        val reply = handle(cmd, lang)
        return when (cmd) {
            is VoiceCommand.Unknown, VoiceCommand.Stop, VoiceCommand.Thanks, VoiceCommand.ShutdownAssistant -> reply
            else -> remember(text, lang, reply)
        }
    }

    /** Выполнить уже разобранную команду (например, вызов инструмента от нейросети). */
    suspend fun executeCommand(cmd: VoiceCommand, text: String, lang: Language): Reply = remember(text, lang, handle(cmd, lang))

    fun hasCustomCommand(text: String): Boolean {
        val normalized = Text.normalize(text)
        return commands.currentCommands().any { c -> c.triggers.any { Text.normalize(it) == normalized } }
    }

    /** Разбор правилами без выполнения: Unknown/Chat отдаём нейросети. */
    fun parse(text: String, lang: Language): VoiceCommand = parser.parse(text, lang)

    private fun remember(text: String, lang: Language, reply: Reply): Reply {
        if (session == null) lastCommand = text to lang
        return reply
    }

    private suspend fun handle(cmd: VoiceCommand, lang: Language): Reply {
        val r = Replies(lang)
        fun say(s: String) = Reply(s, lang)
        return when (cmd) {
            VoiceCommand.Repeat -> say(r.nothingToRepeat)
            VoiceCommand.Thanks -> say(r.thanks)
            VoiceCommand.Stop -> say(r.ok)
            VoiceCommand.ShutdownAssistant -> Reply(r.bye, lang, shutdown = true)
            VoiceCommand.PowerMenu -> say(r.powerMenu(device.openPowerMenu()))
            VoiceCommand.ClearMemory -> say(r.memory(device.clearMemory()))

            VoiceCommand.MusicPlay -> say(playMusic(r))
            VoiceCommand.MusicResume -> { device.mediaPlayPause(play = true); say(r.musicResume) }
            VoiceCommand.MusicPause -> { device.mediaPlayPause(play = false); say(r.musicPause) }
            VoiceCommand.MusicNext -> { device.mediaSkip(next = true); say(r.musicNext) }
            VoiceCommand.MusicPrevious -> { device.mediaSkip(next = false); say(r.musicPrev) }

            is VoiceCommand.VolumeSet -> say(r.volume(device.setVolume(cmd.percent)))
            VoiceCommand.VolumeUp -> say(r.volumeUp(device.adjustVolume(up = true)))
            VoiceCommand.VolumeDown -> say(r.volumeDown(device.adjustVolume(up = false)))
            is VoiceCommand.Flashlight -> say(r.flashlight(cmd.on, device.setFlashlight(cmd.on)))
            VoiceCommand.Battery -> say(r.battery(device.batteryLevel()))
            is VoiceCommand.OpenSettings -> say(if (device.openSettings(cmd.panel)) r.settings else r.settingsFailed)

            VoiceCommand.TellTime -> say(r.time(now()))
            VoiceCommand.TellDate -> say(r.date(now().toLocalDate()))

            is VoiceCommand.Math -> say(
                when (val m = cmd.result) {
                    is MathResult.Value -> r.math(m.format())
                    MathResult.DivisionByZero -> r.divByZero
                    MathResult.Invalid -> r.mathFailed
                },
            )

            is VoiceCommand.CreateEvent -> startEvent(cmd, lang)
            is VoiceCommand.QueryEvents -> {
                val zone = ZoneId.systemDefault()
                val from = cmd.range.start.atStartOfDay(zone).toInstant().toEpochMilli()
                val to = cmd.range.endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val list = events.currentEvents().filter { it.timestamp in from until to }.sortedBy { it.timestamp }
                say(r.events(cmd.range, list))
            }

            is VoiceCommand.OpenApp -> say(openApp(cmd.name, r))
            is VoiceCommand.SetTimer -> say(r.timer(cmd.seconds, device.setTimer(cmd.seconds, cmd.label)))
            is VoiceCommand.Draw -> say(draw(cmd.prompt, r))
            is VoiceCommand.Chat -> say(
                when (cmd.kind) {
                    SmallTalk.GREETING -> r.greeting
                    SmallTalk.HOW_ARE_YOU -> r.howAreYou
                    SmallTalk.WHO_ARE_YOU -> r.whoAreYou
                    SmallTalk.WHAT_CAN_YOU_DO -> r.whatCanYouDo
                },
            )
            is VoiceCommand.Unknown -> say(r.unknown)
        }
    }

    private fun playMusic(r: Replies): String {
        if (device.isMusicActive()) {
            device.mediaPlayPause(play = true); return r.musicResume
        }
        val alias = commands.currentCommands().firstOrNull { it.isSystem && it.id == SYSTEM_MUSIC_ID }
        val pkg = alias?.packageName
        if (pkg != null) return if (device.openApp(pkg)) r.musicStart else r.musicNoApp
        // приложение не выбрано — пробуем «play» медиакнопкой (продолжит последний плеер)
        device.mediaPlayPause(play = true)
        return r.musicStart
    }

    private fun draw(prompt: String?, r: Replies): String {
        if (prompt != null && !safety.isAllowed(prompt)) return r.drawRefused
        val launcher = drawLauncher ?: return r.drawUnsupported
        return when (launcher.draw(prompt)) {
            DrawLauncher.Result.STARTED -> r.drawStarted(prompt.orEmpty())
            DrawLauncher.Result.NEEDS_MODEL -> r.drawNeedsModel
            DrawLauncher.Result.OPENED -> r.drawWhat
            DrawLauncher.Result.BUSY -> r.drawBusy
            DrawLauncher.Result.UNSUPPORTED -> r.drawUnsupported
        }
    }

    private fun openApp(name: String, r: Replies): String =
        when (val m = AppMatcher.find(name, commands.currentCommands(), device.launchableApps())) {
            is AppMatcher.Match.Alias -> openAliasText(m.command, r)
            is AppMatcher.Match.App -> if (device.openApp(m.packageName)) r.opening(m.label) else r.appNotFound(name)
            AppMatcher.Match.None -> r.appNotFound(name)
        }

    private fun openAlias(c: CustomCommand, lang: Language) = Reply(openAliasText(c, Replies(lang)), lang)

    private fun openAliasText(c: CustomCommand, r: Replies): String {
        val pkg = c.packageName ?: return r.aliasNotConfigured
        return if (device.openApp(pkg)) r.opening(c.appLabel ?: c.title) else r.cantOpen
    }

    // ---------------- события ----------------

    private suspend fun startEvent(cmd: VoiceCommand.CreateEvent, lang: Language): Reply {
        val spec = cmd.spec
        val dt = spec?.resolve(now())
        val s = when {
            dt != null && cmd.title != null -> EventSession(EventSession.Step.AWAITING_REMINDER, dateTime = dt, title = cmd.title)
            dt != null -> EventSession(EventSession.Step.AWAITING_TITLE, dateTime = dt)
            spec?.date != null -> EventSession(EventSession.Step.AWAITING_TIME, date = spec.date, title = cmd.title)
            else -> EventSession(EventSession.Step.AWAITING_WHEN, title = cmd.title)
        }
        return ask(s, lang)
    }

    private fun ask(s: EventSession, lang: Language): Reply {
        session = s
        val r = Replies(lang)
        val q = when (s.step) {
            EventSession.Step.AWAITING_WHEN -> r.askWhen
            EventSession.Step.AWAITING_TIME -> r.askTime
            EventSession.Step.AWAITING_TITLE -> r.askTitle
            EventSession.Step.AWAITING_REMINDER -> r.askReminder
        }
        return Reply(q, lang, keepListening = true)
    }

    private suspend fun continueEvent(s: EventSession, text: String, lang: Language): Reply {
        val r = Replies(lang)
        val k = Keywords.of(lang)
        val norm = Text.normalize(text)
        if (Text.hasAny(norm, k.cancel)) {
            session = null
            return Reply(r.eventCancelled, lang)
        }
        return when (s.step) {
            EventSession.Step.AWAITING_WHEN, EventSession.Step.AWAITING_TIME -> {
                val spec: DateTimeSpec = DateTimeParser.parse(norm, now())?.withDate(s.date)
                    ?: return Reply(r.badTime, lang, keepListening = true)
                val dt = spec.resolve(now())
                when {
                    dt == null && spec.date != null -> ask(s.copy(step = EventSession.Step.AWAITING_TIME, date = spec.date), lang)
                    dt == null -> Reply(r.badTime, lang, keepListening = true)
                    !dt.isAfter(now()) -> Reply(r.pastTime, lang, keepListening = true)
                    s.title != null -> ask(s.copy(step = EventSession.Step.AWAITING_REMINDER, dateTime = dt), lang)
                    else -> ask(s.copy(step = EventSession.Step.AWAITING_TITLE, dateTime = dt), lang)
                }
            }
            EventSession.Step.AWAITING_TITLE -> ask(s.copy(step = EventSession.Step.AWAITING_REMINDER, title = Text.capitalize(text.trim())), lang)
            EventSession.Step.AWAITING_REMINDER -> {
                val minutes = parseReminderMinutes(norm, lang)
                    ?: return Reply(r.askReminder, lang, keepListening = true)
                val dt = s.dateTime ?: LocalDateTime.of(s.date ?: now().toLocalDate(), LocalTime.NOON)
                val event = Event(
                    id = UUID.randomUUID().toString(),
                    title = s.title ?: "Событие",
                    timestamp = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    reminderMinutes = minutes,
                )
                events.add(event)
                reminders.schedule(event)
                session = null
                Reply(r.eventSaved(event), lang)
            }
        }
    }

    /** «за 10 минут», «за час», "15 minutes", «не надо» → 0. */
    fun parseReminderMinutes(text: String, lang: Language): Int? {
        val k = Keywords.of(lang)
        val n = NumberWords.first(text)
        val hours = Text.hasAny(text, listOf("час*", "hour*"))
        return when {
            n != null && hours -> (n * 60).toInt()
            n != null -> n.toInt()
            hours -> 60 // «за час», "an hour"
            Text.hasAny(text, listOf("полчаса", "half an hour")) -> 30
            Text.hasAny(text, k.noWords) -> 0
            else -> null
        }
    }

    companion object {
        const val SYSTEM_MUSIC_ID = "system_music"
        const val SYSTEM_BANK_ID = "system_bank"
        const val SYSTEM_BROWSER_ID = "system_browser"
    }
}
