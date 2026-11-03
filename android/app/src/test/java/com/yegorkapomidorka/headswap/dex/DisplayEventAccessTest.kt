// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method

class DisplayEventAccessTest {
    open class BaseEvent {
        var value = 0
        var writes = 0
        open fun setDisplayId(id: Int) { writes++; value = id }
        open fun getDisplayId(): Int = value
    }
    class Motion : BaseEvent() {
        override fun setDisplayId(id: Int) = super.setDisplayId(id)
        override fun getDisplayId(): Int = super.getDisplayId()
    }
    class Key : BaseEvent() {
        override fun setDisplayId(id: Int) = super.setDisplayId(id)
        override fun getDisplayId(): Int = super.getDisplayId()
    }
    class InheritedKey : BaseEvent()
    class SetterOnly : BaseEvent() {
        override fun setDisplayId(id: Int) = super.setDisplayId(id)
    }
    class RejectedSetter : BaseEvent() {
        val rejection = SecurityException("Framework denied this event")
        override fun setDisplayId(id: Int) { writes++; throw rejection }
        override fun getDisplayId(): Int = value
    }
    class RejectedGetter : BaseEvent() {
        val rejection = IllegalStateException("Framework getter failed")
        override fun setDisplayId(id: Int) = super.setDisplayId(id)
        override fun getDisplayId(): Int = throw rejection
    }
    class IgnoredSetter : BaseEvent() {
        override fun setDisplayId(id: Int) { writes++ }
        override fun getDisplayId(): Int = value
    }
    class RedirectedSetter : BaseEvent() {
        override fun setDisplayId(id: Int) { writes++; value = id + 1 }
        override fun getDisplayId(): Int = value
    }
    open class WrongReturnBase {
        fun setDisplayId(id: Int) { require(id > 0) }
        fun getDisplayId(): Long = 4L
    }
    class WrongReturnMotion : WrongReturnBase()
    class WrongReturnKey : WrongReturnBase()
    open class TextMotion : BaseEvent() {
        override fun setDisplayId(id: Int) = super.setDisplayId(id)
        override fun toString(): String = "MotionEvent { action=ACTION_MOVE, displayId=$value, eventId=-432868612 }"
    }
    class SubclassTextMotion : TextMotion() {
        override fun toString(): String = "MotionEvent { displayId=77 }"
    }
    class TextKey : BaseEvent() {
        override fun setDisplayId(id: Int) = super.setDisplayId(id)
        override fun toString(): String = "KeyEvent { action=ACTION_UP, source=0x0, displayId=$value }"
    }

    private fun access(motion: Class<*> = Motion::class.java, key: Class<*> = Key::class.java,
                       lookup: (Class<*>, String, Array<Class<*>>) -> Method = { type, name, args -> type.getDeclaredMethod(name, *args) }) =
        DisplayEventMethods(motion, key, BaseEvent::class.java, lookup)

    @Test fun concreteOverridesWorkWhenBaseDeclarationsAreHidden() {
        val methods = access { type, name, args ->
            if (type == BaseEvent::class.java) throw NoSuchMethodException("Android hides base $name")
            type.getDeclaredMethod(name, *args)
        }
        methods.preflight()
        val motion = Motion(); val key = Key()
        methods.setDisplayId(motion, 47); methods.setDisplayId(key, 53)
        assertEquals(47, methods.getDisplayId(motion)); assertEquals(53, methods.getDisplayId(key))
        assertEquals(1, motion.writes); assertEquals(1, key.writes)
    }

    @Test fun baseFallbackSupportsInheritedMethodsAndMixedConcreteGetterSetter() {
        val methods = access(SetterOnly::class.java, InheritedKey::class.java)
        methods.preflight()
        val motion = SetterOnly(); val key = InheritedKey()
        methods.setDisplayId(motion, 4); methods.setDisplayId(key, 9)
        assertEquals(4, methods.getDisplayId(motion)); assertEquals(9, methods.getDisplayId(key))
    }

    @Test fun preflightChecksKeyEvenBeforeAnyMotionEventIsUsed() {
        val methods = access { type, name, args ->
            if (type == Key::class.java || type == BaseEvent::class.java) throw NoSuchMethodException("hidden $name")
            type.getDeclaredMethod(name, *args)
        }
        val failure = assertThrows(NoSuchMethodException::class.java) { methods.preflight() }
        assertTrue(failure.message!!.contains(Key::class.java.name))
        val event = Motion()
        assertThrows(NoSuchMethodException::class.java) { methods.setDisplayId(event, 5) }
        assertEquals(0, event.writes)
    }

    @Test fun displayZeroAndInvalidIdsCannotMutateEvent() {
        val methods = access(); val event = Motion()
        for (id in listOf(0, -1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { methods.setDisplayId(event, id) }
        }
        assertEquals(0, event.writes)
    }

    @Test fun unsupportedEventCannotReachASetter() {
        val methods = access(); val other = BaseEvent()
        assertThrows(IllegalArgumentException::class.java) { methods.setDisplayId(other, 5) }
        assertThrows(IllegalArgumentException::class.java) { methods.getDisplayId(other) }
        assertEquals(0, other.writes)
    }

    @Test fun setterFailurePropagatesOriginalCauseWithoutRetryOrFallback() {
        val methods = access(RejectedSetter::class.java); val event = RejectedSetter()
        methods.preflight()
        assertSame(event.rejection, assertThrows(SecurityException::class.java) { methods.setDisplayId(event, 7) })
        assertEquals(1, event.writes)
    }

    @Test fun getterFailureAfterAssignmentPreventsSuccessfulReturn() {
        val methods = access(RejectedGetter::class.java); val event = RejectedGetter()
        assertSame(event.rejection, assertThrows(IllegalStateException::class.java) { methods.setDisplayId(event, 7) })
        assertEquals(1, event.writes)
    }

    @Test fun unchangedPrimaryDisplayAndRedirectedDisplayFailReadback() {
        val ignored = IgnoredSetter()
        assertThrows(IllegalStateException::class.java) { access(IgnoredSetter::class.java).setDisplayId(ignored, 8) }
        assertEquals(1, ignored.writes)
        val redirected = RedirectedSetter()
        assertThrows(IllegalStateException::class.java) { access(RedirectedSetter::class.java).setDisplayId(redirected, 8) }
        assertEquals(1, redirected.writes)
    }

    @Test fun wrongFrameworkReturnTypeFailsPreflight() {
        val methods = DisplayEventMethods(WrongReturnMotion::class.java, WrongReturnKey::class.java, WrongReturnBase::class.java)
        assertThrows(IllegalStateException::class.java) { methods.preflight() }
    }

    @Test fun lookupSecurityExceptionDoesNotTryBaseFallback() {
        val denied = SecurityException("Reflection unavailable")
        var baseLookups = 0
        val methods = access { type, _, _ ->
            if (type == BaseEvent::class.java) baseLookups++
            throw denied
        }
        assertSame(denied, assertThrows(SecurityException::class.java) { methods.preflight() })
        assertEquals(0, baseLookups)
    }

    @Test fun hiddenGettersUseStrictReadbackForBothExactEventTypes() {
        val methods = access(TextMotion::class.java, TextKey::class.java) { type, name, args ->
            if (name == "getDisplayId") throw NoSuchMethodException("API33 hides getter")
            type.getDeclaredMethod(name, *args)
        }
        methods.preflight()
        val motion = TextMotion(); val key = TextKey()
        methods.setDisplayId(motion, 71); methods.setDisplayId(key, 83)
        assertEquals(71, methods.getDisplayId(motion)); assertEquals(83, methods.getDisplayId(key))
        assertEquals(1, motion.writes); assertEquals(1, key.writes)
    }

    @Test fun lookupDeniedGetterCanUsePublicTextButInvocationFailureCannot() {
        val methods = access(TextMotion::class.java, TextKey::class.java) { type, name, args ->
            if (name == "getDisplayId") throw SecurityException("Getter lookup denied")
            type.getDeclaredMethod(name, *args)
        }
        val motion = TextMotion(); methods.setDisplayId(motion, 19)
        assertEquals(19, methods.getDisplayId(motion))
        // Existing RejectedGetter also has public toString, but invocation failure is propagated.
        val rejected = RejectedGetter()
        assertSame(rejected.rejection, assertThrows(IllegalStateException::class.java) {
            access(RejectedGetter::class.java).setDisplayId(rejected, 19)
        })
    }

    @Test fun diagnosticReadbackRefusesEventSubclasses() {
        val methods = access(TextMotion::class.java, TextKey::class.java) { type, name, args ->
            if (name == "getDisplayId") throw NoSuchMethodException()
            type.getDeclaredMethod(name, *args)
        }
        assertThrows(IllegalStateException::class.java) { methods.setDisplayId(SubclassTextMotion(), 77) }
    }

    @Test fun actualApi33MotionAndKeyFormatsReadBackPositiveId() {
        val motion = "MotionEvent { action=ACTION_DOWN, actionButton=0, id[0]=0, x[0]=10.0, y[0]=20.0, toolType[0]=TOOL_TYPE_FINGER, buttonState=0, classification=NONE, metaState=0, flags=0x0, edgeFlags=0x0, pointerCount=1, historySize=0, eventTime=1234, downTime=1234, deviceId=0, source=0x2, displayId=7, eventId=-432868612 }"
        val key = "KeyEvent { action=ACTION_UP, keyCode=KEYCODE_BACK, scanCode=0, metaState=0, flags=0x0, repeatCount=0, eventTime=1234, downTime=1234, deviceId=-1, source=0x0, displayId=7 }"
        assertEquals(7, DisplayEventText.readDisplayId(motion, "MotionEvent"))
        assertEquals(7, DisplayEventText.readDisplayId(key, "KeyEvent"))
    }

    @Test fun ambiguousMissingOrSubstringFieldCannotPassDiagnosticReadback() {
        val rejected = listOf(
            "MotionEvent { sourceDeviceId=7 }",
            "MotionEvent { displayIdentifier=7 }",
            "MotionEvent { displayId=7, displayId=7 }",
            "MotionEvent { displayId=7, displayId:8 }",
            "MotionEvent { displayId =7 }",
            "MotionEvent { characters=\"displayId=7\" }",
            "KeyEvent { displayId=7 }",
            "MotionEvent { displayId=7 } trailing",
            "MotionEvent { displayId=7, note=" + "x".repeat(4096) + " }",
        )
        for (text in rejected) assertThrows(IllegalStateException::class.java) {
            DisplayEventText.readDisplayId(text, "MotionEvent")
        }
        assertEquals(7, DisplayEventText.readDisplayId("MotionEvent { sourceDeviceId=99, displayId=7 }", "MotionEvent"))
    }

    @Test fun invalidOrNonPositiveNumericDiagnosticIdsFailClosed() {
        for (raw in listOf("0", "-1", "2147483648", "7.0", "+7", "0x7", "007", "7e0", "7 extra", "")) {
            assertThrows(IllegalStateException::class.java) {
                DisplayEventText.readDisplayId("KeyEvent { displayId=$raw }", "KeyEvent")
            }
        }
        assertEquals(Int.MAX_VALUE, DisplayEventText.readDisplayId("KeyEvent { displayId=2147483647 }", "KeyEvent"))
    }
}
