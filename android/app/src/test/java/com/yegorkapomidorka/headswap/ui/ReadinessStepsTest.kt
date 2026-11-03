package com.yegorkapomidorka.headswap.ui

import com.yegorkapomidorka.headswap.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReadinessStepsTest {
    private val ids get() = Readiness.STEPS.map { it.id }

    @Test
    fun unknownSourcesFollowsAaDev() {
        assertEquals(ids.indexOf(StepId.AA_DEV) + 1, ids.indexOf(StepId.AA_UNKNOWN))
    }

    @Test
    fun everyStepListedOnceAndKeysUnique() {
        // выбор режима и шаги лаунчера лежат в EXTRA_STEPS: чеклист и путь Android Auto остаются прежними
        val all = ids + Readiness.EXTRA_STEPS.map { it.id }
        assertEquals(StepId.values().toSet(), all.toSet())
        assertEquals(all.size, all.toSet().size)
        assertEquals(StepId.values().size, StepId.values().map { it.key }.toSet().size)
    }

    @Test
    fun unknownSourcesIsOptionalAndManual() {
        val s = Readiness.info(StepId.AA_UNKNOWN)
        assertEquals("aa_unknown", s.id.key)
        // «Уже сделано» ведёт дальше и ставит ручную отметку; шаг ничего не требует и не блокирует онбординг
        assertEquals(R.string.onb_already_done, s.skip)
        assertEquals(R.string.step_aa_unknown_hint, s.hint)
        assertNull(s.extra)
        assertEquals(R.string.step_aa_unknown_short, s.short)
    }

    @Test
    fun serverStepIsManualWithoutExtra() {
        // службы спецвозможностей нет: выполненность шага - только ручная отметка «Понятно»
        val s = Readiness.info(StepId.AUTOSTART)
        assertEquals(R.string.onb_got_it, s.skip)
        assertEquals(R.string.step_autostart_primary, s.primary)
        assertNull(s.extra)
    }

    @Test
    fun onlyCarStepHasBodyInsteadOfList() {
        for (s in Readiness.STEPS) {
            if (s.id == StepId.CAR) {
                assertNull(s.list)
                assertEquals(R.string.step_car_body, s.body)
            } else {
                assertNotNull(s.id.key, s.list)
                assertNull(s.id.key, s.body)
            }
        }
    }

    @Test
    fun extraOnlyOnBackgroundStep() {
        assertEquals(listOf(StepId.BACKGROUND), Readiness.STEPS.filter { it.extra != null }.map { it.id })
        // шаги 1 и 5 без подсказки
        assertNull(Readiness.info(StepId.AA_DEV).hint)
        assertNull(Readiness.info(StepId.BACKGROUND).hint)
    }

    @Test
    fun carIsLastAndSkippable() {
        assertEquals(StepId.CAR, ids.last())
        assertEquals(StepId.FIRST, ids[ids.size - 2])
        assertEquals(R.string.onb_skip, Readiness.info(StepId.CAR).skip)
    }
}
