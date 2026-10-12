package com.hakkiiler.voiceassistant.domain.usecases

import com.hakkiiler.voiceassistant.domain.model.Event
import com.hakkiiler.voiceassistant.domain.model.Language
import com.hakkiiler.voiceassistant.domain.model.RangeKind
import com.hakkiiler.voiceassistant.domain.model.EventRange
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Все ответы ассистента на двух языках. */
class Replies(private val lang: Language) {
    private fun t(ru: String, en: String) = if (lang == Language.RU) ru else en

    val nothingToRepeat get() = t("Я ещё ничего не делал.", "I haven't done anything yet.")
    val thanks get() = t("Пожалуйста! Обращайся.", "You're welcome!")
    val ok get() = t("Хорошо.", "Okay.")
    val bye get() = t("Выключаюсь. Пока!", "Shutting down. Bye!")
    val listening get() = t("Слушаю.", "Yes?")
    val greeting get() = t("Привет! Чем помочь?", "Hi! How can I help?")
    val howAreYou get() = t("Всё отлично, работаю. У тебя как?", "All good, I'm working. How about you?")
    val whoAreYou get() = t(
        "Я твой голосовой помощник. Понимаю русский и английский и работаю без интернета.",
        "I'm your voice assistant. I understand Russian and English and work offline.",
    )
    val whatCanYouDo get() = t(
        "Открываю приложения, считаю примеры, управляю музыкой, громкостью и фонариком, говорю заряд, время и дату, создаю события и напоминания, а ещё рисую картинки: скажи «нарисуй» и что именно.",
        "I can open apps, do math, control music, volume and the flashlight, tell the battery level, time and date, create events and reminders, and draw pictures: just say \"draw\" and what.",
    )
    val unknown get() = t(
        "Не понял команду. Скажи «что ты умеешь», чтобы узнать, что я могу.",
        "Sorry, I didn't get that. Say \"what can you do\" to hear my commands.",
    )

    // ---- рисование ----
    fun drawStarted(prompt: String) = t("Рисую «$prompt». Это займёт около минуты.", "Drawing \"$prompt\". It will take about a minute.")
    val drawWhat get() = t("Что нарисовать? Открыл экран рисования.", "What should I draw? I've opened the drawing screen.")
    val drawNeedsModel get() = t(
        "Сначала нужно один раз скачать модель для рисования, около двух гигабайт, и подготовить её. Открыл экран рисования.",
        "First I need to download the drawing model once, about two gigabytes, and prepare it. I've opened the drawing screen.",
    )
    val drawBusy get() = t("Я ещё рисую предыдущую картинку. Подожди немного.", "I'm still working on the previous picture. Please wait.")
    val drawUnsupported get() = t("На этом устройстве рисование не поддерживается.", "Drawing isn't supported on this device.")
    val drawRefused get() = t(
        "Такое я рисовать не буду: запрос похож на незаконный контент с несовершеннолетними.",
        "I won't draw that: the request looks like illegal content involving minors.",
    )

    fun volume(p: Int) = t("Громкость $p%", "Volume $p%")
    fun volumeUp(p: Int) = t("Прибавил, теперь $p%", "Turned it up to $p%")
    fun volumeDown(p: Int) = t("Убавил, теперь $p%", "Turned it down to $p%")
    fun flashlight(on: Boolean, ok: Boolean) = when {
        !ok -> t("Не вышло с фонариком.", "Couldn't control the flashlight.")
        on -> t("Фонарик включён.", "Flashlight on.")
        else -> t("Фонарик выключен.", "Flashlight off.")
    }
    fun battery(level: Int) = t("Заряд $level%", "Battery is at $level%")
    fun memory(closed: Int) = if (closed > 0) t("Очистил память.", "Memory cleared.") else t("Всё чисто.", "Nothing to clean.")
    val settings get() = t("Открываю настройки.", "Opening settings.")
    val settingsFailed get() = t("Не получилось открыть настройки.", "Couldn't open settings.")
    fun powerMenu(ok: Boolean) = if (ok) t("Открываю меню питания.", "Opening the power menu.")
    else t("Не могу выключить телефон без рут-прав.", "I can't power off the phone without root.")

    val musicResume get() = t("Слушаем!", "Playing.")
    val musicStart get() = t("Включаю музыку.", "Starting music.")
    val musicNoApp get() = t("Не нашёл музыкальное приложение. Выбери его в разделе «Команды».", "No music app found. Pick one in the Commands tab.")
    val musicPause get() = t("На паузе.", "Paused.")
    val musicNext get() = t("Следующий.", "Next track.")
    val musicPrev get() = t("Предыдущий.", "Previous track.")

    fun time(now: LocalDateTime) = t("Сейчас ${now.format(HM)}", "It's ${now.format(H12)}")
    fun date(today: LocalDate) = t(
        "Сегодня ${today.dayOfWeek.getDisplayName(TextStyle.FULL, lang.locale)}, ${today.format(DateTimeFormatter.ofPattern("d MMMM", lang.locale))}",
        "Today is ${today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", lang.locale))}",
    )

    fun math(result: String) = t("Будет $result", "That's $result")
    val divByZero get() = t("На ноль делить нельзя, ты чего?", "You can't divide by zero!")
    val mathFailed get() = t("Не вышло посчитать.", "I couldn't calculate that.")

    fun opening(label: String) = t("Окей, открываю $label", "Opening $label")
    fun appNotFound(name: String) = t("Не нашёл у тебя «$name».", "I couldn't find \"$name\".")
    val aliasNotConfigured get() = t("Приложение для этой команды не выбрано. Настрой его в разделе «Команды».",
        "No app is set for this command. Set it in the Commands tab.")
    val cantOpen get() = t("Не могу запустить.", "I can't launch it.")

    // события
    val askWhen get() = t("Окей, на когда планируем?", "Okay, when should I schedule it?")
    val askTime get() = t("Во сколько?", "At what time?")
    val askTitle get() = t("Как назовём событие?", "What should I call it?")
    val askReminder get() = t("За сколько минут напомнить?", "How many minutes before should I remind you?")
    val badTime get() = t("Не совсем понял время. Скажи, например, «завтра в пять вечера».",
        "I didn't catch the time. Say something like \"tomorrow at 5 pm\".")
    val pastTime get() = t("Это время уже прошло. Назови другое.", "That time has already passed. Pick another one.")
    val eventCancelled get() = t("Окей, отменяю создание.", "Okay, cancelled.")
    fun eventSaved(e: Event) = buildString {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.timestamp), ZoneId.systemDefault())
        append(t("Готово! «${e.title}» ${dayPhrase(dt.toLocalDate())} в ${dt.format(HM)}",
            "Done! \"${e.title}\" ${dayPhrase(dt.toLocalDate())} at ${dt.format(H12)}"))
        if (e.reminderMinutes > 0) append(t(", напомню за ${e.reminderMinutes} мин.", ", I'll remind you ${e.reminderMinutes} min before."))
        else append(".")
    }

    fun events(range: EventRange, events: List<Event>): String {
        val what = when (range.kind) {
            RangeKind.THIS_WEEK -> t("эту неделю", "this week")
            RangeKind.NEXT_WEEK -> t("следующую неделю", "next week")
            RangeKind.DAY -> dayPhrase(range.start)
        }
        if (events.isEmpty()) return t("На $what планов нет.", "No plans for $what.")
        val items = events.joinToString("; ") { e ->
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.timestamp), ZoneId.systemDefault())
            val day = if (range.kind == RangeKind.DAY) "" else dt.dayOfWeek.getDisplayName(TextStyle.FULL, lang.locale) + " "
            "$day${dt.format(if (lang == Language.RU) HM else H12)} — ${e.title}"
        }
        return t("На $what у тебя: $items", "For $what you have: $items")
    }

    private fun dayPhrase(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> t("сегодня", "today")
            today.plusDays(1) -> t("завтра", "tomorrow")
            today.plusDays(2) -> t("послезавтра", "the day after tomorrow")
            else -> d.format(DateTimeFormatter.ofPattern(if (lang == Language.RU) "d MMMM" else "MMMM d", lang.locale))
        }
    }

    companion object {
        private val HM = DateTimeFormatter.ofPattern("HH:mm")
        private val H12 = DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)
    }

    fun timer(seconds: Int, ok: Boolean): String {
        if (!ok) return t("Не получилось поставить таймер: нет приложения «Часы».", "Couldn't set a timer: no clock app.")
        val m = seconds / 60
        val sec = seconds % 60
        return if (lang == Language.RU) {
            "Таймер на " + listOfNotNull(
                if (m > 0) "$m мин" else null, if (sec > 0) "$sec сек" else null,
            ).joinToString(" ") + " запущен."
        } else {
            "Timer set for " + listOfNotNull(
                if (m > 0) "$m min" else null, if (sec > 0) "$sec sec" else null,
            ).joinToString(" ") + "."
        }
    }
}
