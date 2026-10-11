package com.hakkiiler.voiceassistant.domain.parser

import com.hakkiiler.voiceassistant.domain.model.Language

/**
 * Ключевые фразы команд для одного языка.
 * "слово" — точное слово/фраза, "основа*" — любое слово с такой основой.
 */
data class LangKeywords(
    val repeat: List<String>,
    val thanks: List<String>,
    val stop: List<String>,
    val shutdownAssistant: List<String>,
    val powerMenu: List<String>,
    val clearMemory: List<String>,
    val eventCreate: List<String>,
    val eventQuery: List<String>,
    val mathTrigger: List<String>,
    val time: List<String>,
    val date: List<String>,
    val musicWord: List<String>,
    val playVerbs: List<String>,
    val pauseVerbs: List<String>,
    val pauseStandalone: List<String>,
    val resume: List<String>,
    val trackWord: List<String>,
    val next: List<String>,
    val previous: List<String>,
    val volumeWord: List<String>,
    val volumeUp: List<String>,
    val volumeDown: List<String>,
    val flashlight: List<String>,
    val on: List<String>,
    val off: List<String>,
    val battery: List<String>,
    val settingsWord: List<String>,
    val wifi: List<String>,
    val bluetooth: List<String>,
    val display: List<String>,
    val languageWord: List<String>,
    val openVerbs: List<String>,
    val fillers: List<String>,
    val greeting: List<String>,
    val howAreYou: List<String>,
    val whoAreYou: List<String>,
    val whatCanYouDo: List<String>,
    val week: List<String>,
    val nextWeek: List<String>,
    val noWords: List<String>,
    val cancel: List<String>,
    val eventFillers: List<String>,
    /** Глаголы рисования: «нарисуй …» / "draw …". */
    val draw: List<String>,
    /** Слова между глаголом и описанием: «нарисуй мне картинку …», "draw me a picture of …". */
    val drawFillers: List<String>,
) {
    /** Все фразы всех команд. */
    fun allPhrases(): List<String> = listOf(
        repeat, thanks, stop, shutdownAssistant, powerMenu, clearMemory, eventCreate, eventQuery, mathTrigger, time, date, musicWord, playVerbs, pauseVerbs, pauseStandalone, resume, trackWord, next, previous, volumeWord, volumeUp, volumeDown, flashlight, on, off, battery, settingsWord, wifi, bluetooth, display, languageWord, openVerbs, fillers, greeting, howAreYou, whoAreYou, whatCanYouDo, week, nextWeek, noWords, cancel, eventFillers, draw,
    ).flatten()
}

object Keywords {
    val RU = LangKeywords(
        repeat = listOf("еще", "еще раз", "повтори", "повтори команду"),
        thanks = listOf("спасибо*", "благодарю", "спс"),
        stop = listOf("стоп", "хватит", "замолчи", "отмена", "отмени", "ничего", "забудь"),
        shutdownAssistant = listOf("выключись", "отключись", "выключи ассистент*", "отключи ассистент*",
            "выключи помощник*", "закончи работу", "перестань слушать", "иди спать"),
        powerMenu = listOf("меню питания", "выключи телефон", "выключи смартфон", "перезагрузи телефон", "перезагрузка"),
        clearMemory = listOf("очисти память", "очисти оперативк*", "освободи память", "закрой все", "закрой приложения",
            "почисти память", "очистка памяти"),
        eventCreate = listOf("создай событие", "добавь событие", "новое событие", "запланируй", "напомни*",
            "создай напоминание", "поставь напоминание", "добавь напоминание", "добавь в календарь", "запиши встречу",
            "создай встречу"),
        eventQuery = listOf("план*", "что у меня", "расписани*", "какие событи*", "какие дела", "мои событи*", "покажи событи*"),
        mathTrigger = listOf("сколько будет", "посчитай", "реши", "вычисли", "подсчитай", "сколько"),
        time = listOf("который час", "сколько времени", "сколько сейчас времени", "какое время", "время сейчас", "текущее время"),
        date = listOf("какое сегодня число", "какое число", "какой сегодня день", "какая дата", "какая сегодня дата", "сегодняшняя дата"),
        musicWord = listOf("музык*", "музон*", "песн*", "плейлист*"),
        playVerbs = listOf("включ*", "запусти", "играй", "поставь", "врубай", "вруби"),
        pauseVerbs = listOf("выключ*", "останов*", "пауз*", "стоп", "выруби"),
        pauseStandalone = listOf("пауза", "поставь на паузу", "на паузу", "стоп музыка"),
        resume = listOf("продолжи*", "играй", "сними с паузы", "сними паузу", "сними с паузу"),
        trackWord = listOf("трек*", "песн*", "композици*"),
        next = listOf("дальше", "следующ*", "вперед", "переключи", "пропусти"),
        previous = listOf("назад", "предыдущ*", "прошл*", "верни"),
        volumeWord = listOf("громкост*", "звук*"),
        volumeUp = listOf("прибав*", "увелич*", "громче", "погромче", "повысь", "подними"),
        volumeDown = listOf("убав*", "уменьш*", "тише", "потише", "понизь", "опусти", "снизь"),
        flashlight = listOf("фонарик*", "фонарь", "вспышк*"),
        on = listOf("включ*", "зажги", "вруби", "посвети"),
        off = listOf("выключ*", "погаси", "выруби", "отключ*"),
        battery = listOf("заряд*", "батаре*", "аккумулятор*", "батарейк*"),
        settingsWord = listOf("настройк*", "параметр*"),
        wifi = listOf("вай фай", "вайфай", "вафли", "wifi", "интернет*", "сет*"),
        bluetooth = listOf("блютуз*", "блютус*", "bluetooth"),
        display = listOf("экран*", "дисплей*", "яркост*"),
        languageWord = listOf("язык*"),
        openVerbs = listOf("открой", "открыть", "запусти", "запустить", "включи", "зайди в", "покажи"),
        fillers = listOf("пожалуйста", "приложение", "приложения", "мне", "ка", "быстро", "давай", "можешь", "сможешь"),
        greeting = listOf("привет*", "здравствуй*", "добрый день", "добрый вечер", "доброе утро", "хай", "салют", "здорово"),
        howAreYou = listOf("как дела", "как ты", "как жизнь", "как поживаешь", "как настроение"),
        whoAreYou = listOf("кто ты", "ты кто", "как тебя зовут", "что ты такое"),
        whatCanYouDo = listOf("что ты умеешь", "что умеешь", "помощь", "помоги", "какие команды", "что ты можешь"),
        week = listOf("недел*"),
        nextWeek = listOf("следующ*"),
        noWords = listOf("нет", "не надо", "не нужно", "без напоминания", "не напоминай", "ноль"),
        cancel = listOf("отмена", "отмени*", "стоп", "хватит", "забудь", "не надо создавать"),
        eventFillers = listOf("создай", "добавь", "новое", "событие", "напоминание", "встречу", "запланируй", "напомни*",
            "поставь", "в календарь", "запиши", "мне", "что", "о", "об", "про", "чтобы", "пожалуйста", "нужно", "надо"),
        draw = listOf("нарисуй", "нарисуйте", "нарисовать", "нарисуешь", "нарисуй ка", "сгенерируй картинку",
            "сгенерируй изображение", "сгенерируй рисунок", "создай картинку", "создай изображение", "создай рисунок",
            "сделай картинку", "сделай рисунок", "изобрази"),
        drawFillers = listOf("пожалуйста", "мне", "нам", "картинку", "картину", "изображение", "рисунок", "можешь", "сможешь",
            "давай", "ка", "где", "на которой", "на котором", "с изображением", "быстро"),
    )

    val EN = LangKeywords(
        repeat = listOf("again", "repeat", "repeat that", "one more time", "do it again"),
        thanks = listOf("thanks", "thank you", "thank", "cheers"),
        stop = listOf("stop", "cancel", "never mind", "nevermind", "shut up", "be quiet", "nothing", "forget it"),
        shutdownAssistant = listOf("turn yourself off", "shut down assistant", "turn off assistant", "turn off the assistant",
            "stop listening", "go to sleep", "shut yourself down", "exit assistant"),
        powerMenu = listOf("power menu", "turn off the phone", "turn off phone", "shut down the phone", "restart the phone",
            "reboot", "restart phone", "power off"),
        clearMemory = listOf("clear memory", "clear the memory", "free memory", "free up memory", "close all apps",
            "close all", "clean up memory", "clear ram", "kill apps"),
        eventCreate = listOf("create event", "create an event", "add event", "add an event", "new event", "schedule a", "schedule an", "schedule meeting",
            "remind me", "set a reminder", "create a reminder", "add a reminder", "set reminder", "add to calendar",
            "create a meeting", "add a meeting"),
        eventQuery = listOf("plans", "my plans", "what do i have", "what's on", "whats on", "what is on", "agenda",
            "schedule for", "my schedule", "my events", "show events", "any events", "events"),
        mathTrigger = listOf("what is", "what's", "whats", "how much is", "calculate", "compute", "solve", "count"),
        time = listOf("what time", "the time", "current time", "time is it", "tell me the time"),
        date = listOf("what's the date", "what is the date", "whats the date", "what date", "what day is", "today's date",
            "todays date", "what's today", "what is today"),
        musicWord = listOf("music", "song*", "playlist*", "tunes"),
        playVerbs = listOf("play", "start", "turn on", "put on"),
        pauseVerbs = listOf("pause", "stop", "turn off"),
        pauseStandalone = listOf("pause", "pause it", "stop the music", "stop music", "pause the music", "pause music"),
        resume = listOf("resume", "continue", "unpause", "keep playing", "play"),
        trackWord = listOf("track*", "song*"),
        next = listOf("next", "skip", "forward"),
        previous = listOf("previous", "back", "last", "prior"),
        volumeWord = listOf("volume", "sound"),
        volumeUp = listOf("louder", "volume up", "turn it up", "increase", "raise", "up", "higher"),
        volumeDown = listOf("quieter", "volume down", "turn it down", "decrease", "lower", "down", "softer", "reduce"),
        flashlight = listOf("flashlight", "flash light", "torch", "flash"),
        on = listOf("on", "turn on", "switch on", "enable", "light up"),
        off = listOf("off", "turn off", "switch off", "disable"),
        battery = listOf("battery", "charge level", "battery level", "power level"),
        settingsWord = listOf("settings", "setting", "preferences"),
        wifi = listOf("wi fi", "wifi", "wireless", "network", "internet"),
        bluetooth = listOf("bluetooth", "blue tooth"),
        display = listOf("display", "screen", "brightness"),
        languageWord = listOf("language", "languages"),
        openVerbs = listOf("open", "launch", "start", "run", "go to", "open up", "show me", "fire up"),
        fillers = listOf("please", "the", "app", "application", "for me", "my", "up", "a", "an", "can you", "could you"),
        greeting = listOf("hello", "hi", "hey", "good morning", "good evening", "good afternoon", "howdy"),
        howAreYou = listOf("how are you", "how's it going", "hows it going", "how are things", "how do you do"),
        whoAreYou = listOf("who are you", "what are you", "what's your name", "what is your name", "whats your name"),
        whatCanYouDo = listOf("what can you do", "help", "what do you do", "commands", "help me"),
        week = listOf("week"),
        nextWeek = listOf("next"),
        noWords = listOf("no", "none", "don't", "dont", "no reminder", "not needed", "nope", "zero"),
        cancel = listOf("cancel", "stop", "never mind", "nevermind", "forget it", "abort"),
        eventFillers = listOf("create", "add", "new", "event", "an", "a", "reminder", "meeting", "schedule", "remind",
            "me", "to", "about", "set", "please", "that", "i", "need", "calendar", "the", "for"),
        draw = listOf("draw", "paint", "sketch", "generate an image", "generate a picture", "generate image",
            "create an image", "create a picture", "make a picture", "make an image", "make a drawing", "imagine"),
        drawFillers = listOf("please", "me", "us", "for me", "a picture of", "a picture", "an image of", "an image",
            "a drawing of", "picture of", "image of", "drawing of", "of", "can you", "could you", "would you", "with"),
    )

    fun of(lang: Language): LangKeywords = if (lang == Language.RU) RU else EN

    /**
     * Известные языку слова: все токены ключевых фраз + числительные + названия приложений.
     * Используется при выборе языка (доля «знакомых» слов в гипотезе).
     */
    fun lexicon(lang: Language): Lexicon {
        val kw = of(lang)
        val all = kw.allPhrases()
        val words = mutableSetOf<String>()
        val stems = mutableSetOf<String>()
        for (phrase in all) for (w in phrase.split(' ')) {
            if (w.endsWith("*")) stems += w.dropLast(1) else words += w
        }
        val isRu = lang == Language.RU
        words += NumberWords.vocabulary().filter { it.first().isCyrillic() == isRu }
        words += MathEvaluator.opVocabulary().filter { it.first().isCyrillic() == isRu }
        words += AppMatcher.slang.keys.flatMap { it.split(' ') }.filter { it.first().isCyrillic() == isRu }
        words += if (isRu) listOf("завтра", "послезавтра", "сегодня", "утра", "вечера", "дня", "ночи", "минут", "минуты", "часов", "часа")
        else listOf("tomorrow", "today", "tonight", "am", "pm", "morning", "evening", "minutes", "hours", "o'clock", "at", "in")
        return Lexicon(words, stems.filter { it.length >= 3 }.toSet())
    }

    private fun Char.isCyrillic() = this in 'а'..'я' || this == 'ё'
}

class Lexicon(private val words: Set<String>, private val stems: Set<String>) {
    fun contains(token: String): Boolean = token in words || stems.any { token.startsWith(it) }
}
