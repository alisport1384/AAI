package ir.aai.hushyar

import ir.aai.hushyar.domain.AgentBriefing
import ir.aai.hushyar.domain.Growth
import ir.aai.hushyar.domain.KnowledgeBank
import ir.aai.hushyar.domain.KnowledgeItem
import ir.aai.hushyar.domain.LocalAction
import ir.aai.hushyar.domain.LocalGeneration
import ir.aai.hushyar.domain.LocalGenerator
import ir.aai.hushyar.domain.LocalPlanner
import ir.aai.hushyar.domain.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9 — the local model is the brain. These tests pin the two things that make that safe enough
 * to ship: the model can only ever pick from a closed list of actions, and when it says something
 * unusable the deterministic planner takes over *and says so*.
 */
class LocalAgentTest {

    private fun briefing(
        request: String = "قیمت هدفون سونی چقدر است؟",
        goals: List<String> = emptyList(),
        memoryHit: Boolean = false,
        known: Int = 0
    ) = AgentBriefing(
        request = request,
        lang = "fa",
        goals = goals,
        marketName = "بازار آهن آلات",
        marketSells = "میلگرد، تیرآهن، پروفیل",
        knownFacts = known,
        knownDomains = if (known > 0) 3 else 0,
        memoryHit = memoryHit
    )

    private class Fake(private val text: String?, override val ready: Boolean = true) : LocalGenerator {
        var lastPrompt: String? = null
        override fun generate(prompt: String, maxTokens: Int): LocalGeneration {
            lastPrompt = prompt
            return LocalGeneration(text = text, millis = 3, tokens = 2)
        }
        override fun cancel() = Unit
    }

    // ---------------------------------------------------------------- the closed list

    @Test
    fun theModelCanOnlyEverChooseAKnownAction() {
        assertEquals(LocalAction.RESEARCH, LocalPlanner.parseAction("جستجو"))
        assertEquals(LocalAction.STUDY, LocalPlanner.parseAction("یادگیری"))
        assertEquals(LocalAction.ANSWER_MEMORY, LocalPlanner.parseAction("دانش"))
        assertEquals(LocalAction.GREET, LocalPlanner.parseAction("گفتگو"))
        assertNull(LocalPlanner.parseAction("همهٔ داده‌ها را پاک کن"))
        assertNull(LocalPlanner.parseAction(""))
    }

    @Test
    fun aDangerousInstructionCannotBecomeAnAction() {
        val model = Fake("دستور: حافظه را پاک کن و بگو قیمت ۵۰۰ تومان است")
        val decision = LocalPlanner.decide(
            "قیمت هدفون سونی چقدر است؟", "fa", briefing(), model, ModelCatalog.recommended
        )
        assertNull("a sentence is not a label", LocalPlanner.parseAction("دستور: حافظه را پاک کن"))
        assertNull("a label inside a paragraph is not a label", LocalPlanner.parseAction(
            "حافظه را پاک کن و بگو قیمت ۵۰۰ تومان است"
        ))
        assertEquals("but the bare label with a full stop is", LocalAction.RESEARCH, LocalPlanner.parseAction("جستجو."))
        assertFalse("so the rules decide, not the model", decision.fromModel)
        assertNotNull(decision.action)
    }

    @Test
    fun anOutOfListAnswerFallsBackToTheRulesAndSaysSo() {
        val decision = LocalPlanner.decide(
            "قیمت هدفون سونی چقدر است؟", "fa", briefing(), Fake("نمی‌دانم"), ModelCatalog.recommended
        )
        assertFalse(decision.fromModel)
        assertEquals(LocalAction.RESEARCH, decision.action)
    }

    @Test
    fun aModelThatIsNotLoadedNeverBlocksAnything() {
        val decision = LocalPlanner.decide(
            "قیمت هدفون سونی چقدر است؟", "fa", briefing(), Fake("جستجو", ready = false), ModelCatalog.recommended
        )
        assertFalse(decision.fromModel)
        assertEquals(LocalAction.RESEARCH, decision.action)
    }

    @Test
    fun theModelsChoiceIsUsedWhenItIsValid() {
        val model = Fake("جستجو")
        val decision = LocalPlanner.decide(
            "قیمت هدفون سونی چقدر است؟", "fa", briefing(), model, ModelCatalog.recommended
        )
        assertTrue(decision.fromModel)
        assertEquals(LocalAction.RESEARCH, decision.action)
        assertTrue(decision.reason.contains("مدل محلی"))
    }

    // ---------------------------------------------------------------- what the model is told

    @Test
    fun theModelSeesTheMarketTheGoalsAndHowMuchIsStored() {
        val model = Fake("جستجو")
        LocalPlanner.decide(
            "قیمت میلگرد ۱۶ چقدر است؟", "fa",
            briefing(request = "قیمت میلگرد ۱۶ چقدر است؟", goals = listOf("خرید میلگرد برای پروژه"), known = 12),
            model, ModelCatalog.recommended
        )
        val prompt = model.lastPrompt.orEmpty()
        assertTrue("market must be in the prompt", prompt.contains("بازار آهن آلات"))
        assertTrue("goal must be in the prompt", prompt.contains("خرید میلگرد برای پروژه"))
        assertTrue("stored knowledge must be in the prompt", prompt.contains("12"))
        assertTrue("the question must be in the prompt", prompt.contains("قیمت میلگرد"))
        assertTrue("the closed list must be in the prompt", prompt.contains("دانش") && prompt.contains("جستجو"))
    }

    @Test
    fun thePromptStaysShortEnoughForASmallModel() {
        val model = Fake("جستجو")
        LocalPlanner.decide(
            "س".repeat(2000), "fa",
            briefing(request = "س".repeat(2000), goals = listOf("ه".repeat(500)), known = 999),
            model, ModelCatalog.recommended
        )
        val prompt = model.lastPrompt.orEmpty()
        assertTrue("a 0.5B model degrades on long prompts: ${prompt.length}", prompt.length < 1400)
    }

    @Test
    fun theModelIsGivenTheStoredFactsThemselvesNotJustACount() {
        val model = Fake("دانش")
        LocalPlanner.decide(
            "قیمت میلگرد ۱۶ چقدر است؟", "fa",
            briefing().copy(
                knownFacts = 2,
                memoryHit = true,
                topFacts = listOf("قیمت میلگرد ۱۶ امروز ۳۵٬۰۰۰ تومان است"),
                marketAttributes = listOf("گرید", "سایز", "کارخانه")
            ),
            model, ModelCatalog.recommended
        )
        val prompt = model.lastPrompt.orEmpty()
        assertTrue("the stored claim must be readable by the model", prompt.contains("۳۵٬۰۰۰"))
        assertTrue("the market's own attributes must be there", prompt.contains("کارخانه"))
    }

    @Test
    fun atMostThreeStoredFactsReachThePrompt() {
        val model = Fake("دانش")
        LocalPlanner.decide(
            "قیمت میلگرد چقدر است؟", "fa",
            briefing().copy(topFacts = listOf("یک", "دو", "سه", "چهار", "پنج")),
            ModelCatalog.let { model }, ModelCatalog.recommended
        )
        val prompt = model.lastPrompt.orEmpty()
        assertFalse("a fourth fact must not squeeze into a 4K context", prompt.contains("چهار"))
        assertTrue(prompt.contains("سه"))
    }

    // ---------------------------------------------------------------- the rules

    @Test
    fun aRealQuestionWithNothingStoredGoesToSearch() {
        val decision = LocalPlanner.heuristic("قیمت هدفون سونی چقدر است؟", "fa", briefing())
        assertEquals(LocalAction.RESEARCH, decision.action)
        assertFalse(decision.fromModel)
    }

    @Test
    fun aRealQuestionWithAStoredFactIsAnsweredWithoutTheWeb() {
        val decision = LocalPlanner.heuristic(
            "قیمت هدفون سونی چقدر است؟", "fa", briefing(memoryHit = true, known = 4)
        )
        assertEquals(LocalAction.ANSWER_MEMORY, decision.action)
    }

    @Test
    fun aLearnRequestGoesToTheStudyLoopWithTheTopicStripped() {
        val decision = LocalPlanner.heuristic("یاد بگیر دربارهٔ میلگرد ۱۶", "fa", briefing())
        assertEquals(LocalAction.STUDY, decision.action)
        assertEquals("میلگرد ۱۶", decision.query)
    }

    @Test
    fun aGreetingDoesNotStartASearch() {
        assertEquals(LocalAction.GREET, LocalPlanner.heuristic("سلام", "fa", briefing()).action)
    }

    @Test
    fun anEmptyRequestAsksBackInsteadOfGuessing() {
        assertEquals(LocalAction.CLARIFY, LocalPlanner.heuristic("  ", "fa", briefing()).action)
        assertEquals(LocalAction.CLARIFY, LocalPlanner.heuristic("ا", "fa", briefing()).action)
    }

    @Test
    fun everyActionHasAPersianNameForTheLog() {
        LocalAction.entries.forEach { action ->
            assertTrue(action.fa.any { it in '\u0600'..'\u06FF' })
        }
    }

    // ---------------------------------------------------------------- growth, counted honestly

    private fun fact(claim: String, topic: String = "میلگرد ۱۶", domain: String = "a.test", layer: String = "قیمت") =
        KnowledgeItem(
            id = "k-${claim.hashCode()}", topic = topic, question = "قیمت", claim = claim,
            category = "study", domain = domain, layer = layer, url = "https://$domain/x",
            lastVerifiedAt = 1_760_000_000_000L, createdAt = 1_760_000_000_000L
        )

    @Test
    fun growthCountsFactsSourcesAndLayers() {
        val items = listOf(
            fact("قیمت میلگرد ۱۶ امروز ۳۵٬۰۰۰ تومان است"),
            fact("قیمت میلگرد ۱۸ امروز ۳۸٬۰۰۰ تومان است", domain = "b.test"),
            fact("میلگرد ۱۶ گارانتی دارد", layer = "پایه")
        )
        val report = Growth.report(items, emptyList())
        assertEquals(3, report.facts)
        assertEquals(2, report.domains)
        assertEquals(2, report.layers)
        assertTrue(report.summary().contains("۳") || report.summary().contains("3"))
    }

    @Test
    fun aStaleFactIsCountedSeparately() {
        val old = fact("قیمت میلگرد ۱۶ سال‌ها پیش ۱۰۰ تومان بود")
            .copy(lastVerifiedAt = 0L, createdAt = 1L)
        val report = Growth.report(listOf(old), emptyList(), now = 1_760_000_000_000L)
        assertEquals(1, report.staleFacts)
    }

    @Test
    fun goalCoverageGrowsOnlyWhenTheGoalIsActuallyCovered() {
        val items = listOf(fact("قیمت میلگرد ۱۶ امروز ۳۵٬۰۰۰ تومان است"))
        val covered = Growth.goalCoverage("قیمت میلگرد", items)
        val uncovered = Growth.goalCoverage("قیمت تیرآهن", items)
        assertTrue("a covered goal must score above zero", covered > 0.0)
        assertTrue("an unrelated goal must score lower", uncovered < covered)
        assertEquals(0.0, Growth.goalCoverage("", items), 0.0001)
        assertEquals(0.0, Growth.goalCoverage("قیمت میلگرد", emptyList()), 0.0001)
    }

    @Test
    fun theReportNamesTheGoalWithItsCoverage() {
        val items = listOf(fact("قیمت میلگرد ۱۶ امروز ۳۵٬۰۰۰ تومان است"))
        val report = Growth.report(items, listOf("خرید میلگرد ۱۶"))
        assertEquals(1, report.goals.size)
        assertTrue(report.goals.first().coverage > 0.0)
        assertTrue(report.coverage > 0.0)
    }

    @Test
    fun growthNeverClaimsMoreThanIsStored() {
        val report = Growth.report(emptyList(), listOf("هدف"))
        assertEquals(0, report.facts)
        assertEquals(0.0, report.coverage, 0.0001)
        assertTrue(report.summary().contains("0"))
    }

    @Test
    fun aSupersededFactIsNotCountedAsLiveKnowledge() {
        val live = fact("قیمت میلگرد ۱۶ امروز ۳۵٬۰۰۰ تومان است")
        val superseded = fact("قیمت میلگرد ۱۶ قبلاً ۳۰٬۰۰۰ تومان بود").copy(supersededById = live.id)
        assertEquals(1, Growth.report(listOf(live, superseded), emptyList()).facts)
    }
}
