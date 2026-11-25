package app.echoread.core.i18n

/**
 * 界面文案的「语言内核」：每一句界面文字在这里声明一次，各语言包（[ZhStrings]、[EnStrings]）完整实现它。
 *
 * - 业务代码只写 `S.shelf.importBooks`、`S.errors.apiKeyRejected(badge)`，不出现任何文案字面量；
 * - 某个语言包漏了一句，**编译不过**，而不是用户在界面上看到漏翻；
 * - 带参数的文案是函数，参数在各语言里各自摆位置（中文「共 N 章」、英文「N chapters」）；
 * - 纯 Kotlin，不依赖 Android：core 层生成的错误文案、单元测试里都能直接用。
 *
 * 新增一句：在对应分组里加一个成员 → 编译器会指出 ZhStrings / EnStrings 里要补的位置。
 * 新增一种语言：照着 EnStrings 写一个新的 object（例如 JaStrings），再在 [I18n] 里接上即可。
 */
interface Strings {
    val common: Common
    val shelf: Shelf
    val importer: Importer
    val reader: Reader
    val settings: Settings
    val gemini: Gemini
    val catalog: Catalog
    val playback: Playback
    val errors: Errors
    val update: Update

    /** 多处共用的短词（按钮、状态） */
    interface Common {
        val standard: String
        val connectionOk: String
        val chinese: String
        val other: String
        val warm: String
        val free: String
        val perTokenPricing: String
        val model: String
        val voice: String
        val service: String
        val notSetUp: String
        val collapse: String
        val expand: String
        val generating: String
        val preview: String
        val refresh: String
        val details: String
        val speech: String
        val speed: String
        val selected: String
        val orEnterAModel: String
        val chooseVoice: String
        val all: String
        val serviceDefault: String
        val pageTurnGestures: String
        val importBooks: String
        val clear: String
        val playbackFailed: String
        val contents: String
        val readingStyle: String
        val voiceSettings: String
        val howToUse: String
        val close: String
    }

    /** 书架、更新卡片 */
    interface Shelf {
        /** 书的篇幅（字数）：中文按「万字」，英文按 k / M */
        fun bookLength(chars: Int): String
        val recent: String
        val added: String
        val title: String
        val sampleImportFailed: String
        fun libraryBooks(booksCount: Any?): String
        val tagline: String
        val geminiKeyBannerTitle: String
        val keyBannerTitle: String
        val geminiKeyBannerSubtitle: String
        val keyBannerSubtitle: String
        val emptyTitle: String
        val emptyHint: String
        val importYourFirstBook: String
        val trySample: String
        val howToUseLink: String
        val searchTitleOrAuthor: String
        fun noResultsFor(query: Any?): String
        val importingBook: String
        val removedFromLibrary: String
        val experimental: String
        fun updateDownloaded(versionName: Any?): String
        val connectingToDownloadServer: String
        fun downloadingUpdate(versionName: Any?, percent: Any?): String
        val updateFailed: String
        fun updateAvailable(versionName: Any?): String
        fun currentVersionShort(currentVersionName: Any?): String
        val install: String
        val allowInstallHint: String
        val updateNow: String
        val retry: String
        val skipThisVersion: String
        val experimentalWarning: String
        val continueReading: String
        fun bookMeta(author: Any?, format: Any?, chapterCount: Any?, length: Any?): String
        val deleteFromLibrary: String
        val notStarted: String
        fun readPercent(percent: Any?): String
        val justNow: String
        fun minAgo(minutes: Any?): String
        fun hoursAgo(hours: Any?): String
        fun daysAgo(days: Any?): String
    }

    /** 导入书籍、扫描、解析器生成的标题 */
    interface Importer {
        val scanFailed: String
        fun importedChapters(title: Any?, chapterCount: Any?): String
        val importFailed: String
        val epubMissingContainer: String
        val epubMissingOpfPath: String
        val epubMissingOpf: String
        fun partTitle(part: Any?): String
        val epubNoText: String
        val opening: String
        val untitled: String
        val folder: String
        val untitledTxt: String
        fun fileTooLarge(sizeMb: Any?, limitMb: Any?): String
        val cantReadTheFile: String
        val onlyTxtAndEpub: String
        val noChaptersCouldBe: String
        val unknownAuthor: String
        fun scanningFound(first: Any?): String
        val noBooksFoundYet: String
        fun foundBooks(candidatesCount: Any?): String
        fun shown(shownCount: Any?): String
        fun checkedFiles(second: Any?): String
        val scanTruncated: String
        val stop: String
        val rescan: String
        val searchFileNameOr: String
        val deselectAll: String
        val selectAll: String
        val scanning: String
        val noBooksScannedHint: String
        val noMatchingBooks: String
        val selectBooksToImport: String
        fun importSelected(pickedCount: Any?): String
        val browseFiles: String
        val readyToScanAll: String
        val changeFolder: String
        val letLectorFindBooks: String
        val grantAccessHint: String
        val allFilesAccessHint: String
        val ifYoudRatherNot: String
        val chooseFolderOrRootHint: String
        val chooseFolderHint: String
        val grantAccess: String
        val enableFullScan: String
        val chooseStorage: String
        val chooseFolder: String
        val grantedFolders: String
        val remove: String
        val inLibrary: String
        val device: String
        val mediaLibrary: String
        val grantedFolder: String
    }

    /** 阅读器、翻页手势 */
    interface Reader {
        val swipeToTurn: String
        val wontConflictWithBack: String
        val leftRight: String
        val upDown: String
        val off: String
        val tipWithGestureNavigation: String
        val tapToTurnZones: String
        val zonesLeftRight: String
        val zonesTopBottom: String
        val topZone: String
        val leftZone: String
        val bottomZone: String
        val rightZone: String
        val topPreviousPage: String
        val leftPreviousPage: String
        val topNextPage: String
        val leftNextPage: String
        val tapToRead: String
        val worksOutsideTapZones: String
        val turnedOff: String
        val on: String
        val tapToReadOffHint: String
        val swipeSensitivity: String
        val high: String
        val low: String
        val sensitivityHint: String
        val resetToDefaults: String
        val prev: String
        val next: String
        val tapToReadShort: String
        val sleep15: String
        val sleep30: String
        val sleep60: String
        val sleep90: String
        val sleepEndOfChapter: String
        val thisIsTheLast: String
        val chapterContentIsMissing: String
        val tapAnyWordTo: String
        val couldntLoadChapter: String
        fun speedLabel(rate: Any?): String
        val thisBookDoesntExist: String
        val backToLibrary: String
        val back: String
        fun pageOf(page: Any?, pageCount: Any?): String
        fun chapterOf(chapter: Any?, chapterCount: Any?): String
        val backToPlaybackButton: String
        val backToPlaybackPosition: String
        val sleepTimer: String
        val sleepChapterShort: String
        val previousChapter: String
        val nextChapter: String
        val pause: String
        val play: String
    }

    /** 朗读设置、阅读样式、配色、帮助、模型与音色选择器 */
    interface Settings {
        val language: String
        val languageSystem: String
        /** 语言名按惯例用本族文字：两个语言包里都是「中文」 */
        val languageZh: String
        val languageEn: String
        val languageHint: String
        val vibrant: String
        val expressive: String
        val neutral: String
        val mono: String
        val fidelity: String
        val content: String
        val rainbow: String
        val fruitSalad: String
        val externalConfigTitle: String
        val externalConfigHint: String
        val cancel: String
        val apply: String
        val settingsApplied: String
        val default: String
        val terracotta: String
        val moss: String
        val violet: String
        val ocean: String
        val amber: String
        val rose: String
        val slate: String
        val appColors: String
        val useWallpaperColors: String
        val wallpaperColorsHint: String
        val requiresAndroid12Or: String
        val mainColor: String
        val style: String
        val contrast: String
        val medium: String
        val high: String
        val reading: String
        val theme: String
        val themePreviewGlyph: String
        val fontSize: String
        val lineSpacing: String
        val paragraphSpacing: String
        val font: String
        val serif: String
        val sansSerif: String
        val haptics: String
        val hapticsHint: String
        val vendorQwen: String
        val vendorCustom: String
        val perMillionChars: String
        fun syncedGeminiVoices(listCount: Any?): String
        fun syncedModels(modelsCount: Any?): String
        val noModelsListed: String
        val enterApiKeyFirst: String
        val playing: String
        val previewFinished: String
        val notSelected: String
        val notSet: String
        val advanced: String
        val charactersPerSegment: String
        val chunkHint: String
        val prefetchSegments: String
        val prefetchHint: String
        val audioCache: String
        fun cacheStats(count: Any?, size: Any?): String
        val calculating: String
        val clear: String
        val audioCacheCleared: String
        val voiceSettingsTitle: String
        val voiceEngine: String
        val aiVoice: String
        val systemTts: String
        val engineOpenAiDesc: String
        val engineGeminiDesc: String
        val engineSystemDesc: String
        val keyHintAutoSync: String
        val waitingToSync: String
        val syncingModelList: String
        val connectedToOpenrouter: String
        val connectedShort: String
        fun voiceModelsSuffix(count: Any?): String
        val balanceSuffix: String
        fun balanceFailedSuffix(badge: Any?): String
        fun suspiciousAuthSuffix(badge: Any?): String
        val customEndpoint: String
        val createOpenRouterKey: String
        val deliveryInstructions: String
        val instructionsPlaceholder: String
        val geminiChunkNote: String
        fun youreUpToDate(currentVersionName: Any?): String
        val updateFoundSeeLibrary: String
        val couldntCheckForUpdates: String
        val checkingForUpdates: String
        val checkForUpdates: String
        fun currentVersion(currentVersionName: Any?): String
        val helpImport1: String
        val helpImport2: String
        val helpImport3: String
        val helpTapToReadTitle: String
        val helpTapToRead: String
        val helpGestures1: String
        val helpGestures2: String
        val tapTheGearAnd: String
        val helpOfflineTitle: String
        val helpOffline: String
        val swipeSideways: String
        val swipeUpDown: String
        val noSwiping: String
        val noTapZones: String
        fun zonesSummaryTopBottom(prevPercent: Any?, nextPercent: Any?): String
        fun zonesSummaryLeftRight(prevPercent: Any?, nextPercent: Any?): String
        val tapToReadSuffix: String
        val chooseModel: String
        val noModelsSyncedHint: String
        val modelIdPlaceholder: String
        val filterModels: String
        val cloning: String
        fun voiceCount(voicesN: Any?): String
        val anyVoiceId: String
        val noMatchingModels: String
        val orEnterAnUnlisted: String
        val voiceId: String
        val orEnterAVoice: String
        val noVoiceListHint: String
        val blankServiceDefault: String
        val dark: String
        val black: String
        val light: String
        val paper: String
        val eyeCare: String
    }

    /** Gemini 原生引擎 */
    interface Gemini {
        val previewText: String
        val flashLangs: String
        val flashNote: String
        val flashPrice: String
        val liteLangs: String
        val liteNote: String
        val litePrice: String
        val notJsonObject: String
        val noAudioReturned: String
        fun statusSuffix(status: Any?): String
        val contentBlocks: String
        fun unplayableMime(mimeType: Any?): String
        val mimeNone: String
        val styleNatural: String
        val styleCalm: String
        val styleStorytelling: String
        val styleSlow: String
        val keyHint: String
        val waitingToConnect: String
        val fetchingTheVoiceCatalog: String
        fun connectedVoices(total: Any?): String
        fun customVoicesSuffix(custom: Any?): String
        val createAKeyIn: String
        val privacyNote: String
        val style: String
        val stylePlaceholder: String
        val styleHint: String
        val chooseAGeminiModel: String
        fun paidTier(price: Any?): String
        val tiersNote: String
        val libraryLoadedHint: String
        val fetchingTheVoiceLibrary: String
        val libraryNotLoadedHint: String
        val noKeyVoicesHint: String
        val fetchVoiceLibrary: String
        val voiceSearchPlaceholder: String
        val noMatchingVoices: String
        val voiceIdField: String
    }

    /** 音色目录与模型信息行 */
    interface Catalog {
        val fishVoiceHint: String
        val minimaxVoiceHint: String
        val myVoices: String
        val multilingual: String
        val english: String
        val japanese: String
        val korean: String
        val spanish: String
        val french: String
        val german: String
        val italian: String
        val dutch: String
        val portuguese: String
        val russian: String
        val hindi: String
        val us: String
        val uk: String
        val brazil: String
        val xiaobei: String
        val xiaoni: String
        val xiaoxiao: String
        val xiaoyi: String
        val yunjian: String
        val yunxi: String
        val yunxia: String
        val yunyang: String
        val bright: String
        val upbeat: String
        val informative: String
        val firm: String
        val excitable: String
        val youthful: String
        val breezy: String
        val easyGoing: String
        val breathy: String
        val clear: String
        val smooth: String
        val gravelly: String
        val upbeatBright: String
        val soft: String
        val firmSolid: String
        val even: String
        val mature: String
        val forward: String
        val friendly: String
        val casual: String
        val gentle: String
        val livelyActive: String
        val knowledgeable: String
        val britishWarm: String
        val brightLively: String
        val calm: String
        val mostNatural: String
        val conversationalA: String
        val conversationalB: String
        val readSpeechA: String
        val readSpeechB: String
        val readSpeechC: String
        val readSpeechD: String
        val modelDefault: String
        val usFemale: String
        val usMale: String
        val ukFemale: String
        val ukMale: String
        val random: String
        val variesEachTime: String
        val chineseEnglish: String
        val gentleSoft: String
        val lowMellow: String
        val beijingDialect: String
        val sichuanDialect: String
        val rhythmic: String
        val playful: String
        val neutral: String
        val happy: String
        val cheerful: String
        val excited: String
        val confident: String
        val curious: String
        val sad: String
        val frustrated: String
        val angry: String
        val sarcastic: String
        val confused: String
        val ashamed: String
        val jealous: String
        val audiobookF1: String
        val audiobookF2: String
        val audiobookM1: String
        val audiobookM2: String
        val femaleHost: String
        val maleHost: String
        val youngGirl: String
        val sweetFemale: String
        val poisedLady: String
        val matureFemale: String
        val shyYoungMan: String
        val eliteYoungMan: String
        val boldYoungMan: String
        val collegeStudent: String
        val fishVoicePlaceholder: String
        val multilingualInclChinese: String
        val kokoroLangs: String
        val openLightweight: String
        val qwenTtsFlash: String
        val mostlyChinese: String
        val alibabaDashscopeTts: String
        val qwenTtsPlus: String
        val alibabaDashscopeHq: String
        val chineseEnglishMore: String
        val fishS21Free: String
        val stageDirectionsInBrackets: String
        val n24Languages: String
        val n130Languages: String
        val flagshipBestForAudiobooks: String
        val n101Languages: String
        val fasterCheaper: String
        val bracketAudioTags: String
        val lowLatencyAudioTags: String
        val n10Languages: String
        val includesBeijingSichuanDialect: String
        val n23Languages: String
        val consistentOverLongReads: String
        val lowLatencyTier: String
        val n90Languages: String
        val mostExpressive: String
        val lowLatency: String
        val n70Languages: String
        val maxCharsPerRequest: String
        val elevenV3Conversational: String
        val n29Languages: String
        val longFormNarrationAudiobooks: String
        val n32Languages: String
        val ultraLowLatency: String
        val emotionPaceTags: String
        val n48Languages: String
        val n20LanguagesAutoDetected: String
        val n15Languages: String
        val n7Languages: String
        val englishFrench: String
        val voxtralEmotionsNote: String
        val cheapestTag: String
        val flagshipTag: String
        val expressiveTag: String
        val longFormTag: String
        val qwenTts: String
        val audiobooksTag: String
        fun pricePerMillionChars(price: Any?): String
        fun voicesCount(n: Any?): String
        val openVoiceIds: String
        val cloning: String
    }

    /** 朗读引擎、系统语音、播放 */
    interface Playback {
        fun providerAskedToWait(retryAfterSec: Any?): String
        fun retryNote(headline: Any?, secs: Any?, attempt: Any?, maxAttempts: Any?, why: Any?): String
        fun segmentsFailedInRow(segmentsFailed: Any?, headline: Any?): String
        fun skippedSegments(count: Any?, badge: Any?): String
        val chapterContentMissing: String
        val playbackInterrupted: String
        val systemTtsFailed: String
        val noAiVoiceService: String
        val synthesisFailed: String
        fun chapterTitle(number: Any?): String
        val albumTitle: String
        fun playbackError(errorCodeName: Any?): String
        val bookNotFound: String
        val sleepTimerEnded: String
        fun emptyAudioBody(contentType: Any?, size: Any?): String
        fun notAudioBody(contentType: Any?, size: Any?): String
        val testPhrase: String
        val systemTtsError: String
        fun systemTtsErrorCode(code: Any?): String
        val systemTtsMissingEngine: String
        val systemTtsUnavailable: String
        val systemTtsRequestFailed: String
        val systemTtsProducedNo: String
    }

    /** 网络错误：标题、建议、详情 */
    interface Errors {
        val badgeNotSet: String
        val badgeNoResponse: String
        val apiKeyNotSet: String
        val baseUrlNotSet: String
        fun apiKeyRejected(badge: Any?): String
        fun outOfQuota(badge: Any?): String
        fun rateLimited(badge: Any?): String
        fun retryAfterSuffix(seconds: Any?): String
        fun notFound(badge: Any?): String
        fun requestRejected(badge: Any?): String
        fun precondition(badge: Any?): String
        fun providerError(badge: Any?): String
        val requestTimedOut: String
        val networkConnectionFailed: String
        fun cantReach(host: Any?): String
        val secureConnectionFailed: String
        fun unreadableResponse(badge: Any?): String
        fun emptyAudioReturned(badge: Any?): String
        fun requestFailed(badge: Any?): String
        val adviceMissingKey: String
        val adviceMissingBaseUrl: String
        val adviceAuth: String
        val adviceGeminiQuota: String
        val adviceQuota: String
        val adviceGeminiRateLimit: String
        val adviceRateLimit: String
        val adviceGeminiNotFound: String
        val adviceNotFound: String
        val adviceBadRequest: String
        val advicePrecondition: String
        val adviceServer: String
        val adviceTimeout: String
        val adviceConnectivity: String
        val adviceTls: String
        val adviceParse: String
        val adviceEmptyAudio: String
        val adviceUnknown: String
        fun detailProviderMessage(message: Any?): String
        fun detailProviderCode(code: Any?): String
        fun detailCategory(category: Any?, statusClass: Any?): String
        fun detailEndpoint(method: Any?, endpoint: Any?): String
        fun detailModel(model: Any?): String
        fun detailTransport(transport: Any?): String
        fun detailAttempt(attempt: Any?, maxAttempts: Any?): String
        fun detailElapsed(elapsedMs: Any?): String
        val detailBody: String
        fun detailSuggestion(advice: Any?): String
        val catAuth: String
        val catQuota: String
        val catRateLimit: String
        val catBadRequest: String
        val catPrecondition: String
        val catNotFound: String
        val catServer: String
        val catTimeout: String
        val catConnectivity: String
        val catTls: String
        val catParse: String
        val catEmptyAudio: String
        val catMissingKey: String
        val catMissingBaseUrl: String
        val catUnknown: String
        val class4xx: String
        val class5xx: String
        val classLocal: String
        val errorDetails: String
        val labelProviderMessage: String
        fun labelCode(code: Any?): String
        val labelRequest: String
        val labelEndpoint: String
        val labelTransport: String
        val labelAttempt: String
        fun attemptOf(attempt: Any?, maxAttempts: Any?): String
        val labelElapsed: String
        fun seconds(seconds: Any?): String
        val labelBodySnippet: String
        val copyDiagnostics: String
        val copied: String
        val gotIt: String
        val lectorDiagnostics: String
    }

    /** 应用内更新 */
    interface Update {
        val manifestFetchFailed: String
        fun updateCheckFailed(reason: Any?): String
        val networkError: String
        val emptyResponse: String
        val checksumMismatch: String
        val saveFailed: String
        val downloadFailed: String
        fun downloadFailedWith(reason: Any?): String
    }
}
