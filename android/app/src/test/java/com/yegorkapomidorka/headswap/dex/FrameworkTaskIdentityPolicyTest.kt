// Ported from SadykovIV/headswap_desk (GPL-3.0), author Ildar Sadykov.
package com.yegorkapomidorka.headswap.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameworkTaskIdentityPolicyTest {
    private fun parse(text: String, taskId: Int = 42) =
        FrameworkTaskIdentityPolicy.fromFrameworkPrefix(text, taskId)

    @Test fun actualApi33EmulatorFrameworkPrefixIsAccepted() {
        // API33 reflection-probe.json: exact RunningTaskInfo, public taskId=10, no readable fields.
        assertEquals(FrameworkTaskIdentityPolicy.Identity(0, 0, 10),
            parse("TaskInfo{userId=0 taskId=10 displayId=0 isRunning=true ", 10))
    }

    @Test fun android13FrameworkPrefixIsReadBeforeApplicationFields() {
        assertEquals(FrameworkTaskIdentityPolicy.Identity(7, 0, 42), parse(
            "TaskInfo{userId=0 taskId=42 displayId=7 isRunning=true baseIntent=Intent { cmp=example/.Home } " +
                "baseActivity=ComponentInfo{example/.Home} topActivity=ComponentInfo{example/.Detail}}"
        ))
    }

    @Test fun requestedVisibleTypesIsReadFromLastSystemWrittenField() {
        assertEquals(8, FrameworkTaskIdentityPolicy.requestedVisibleTypes(
            "TaskInfo{userId=0 taskId=42 displayId=7 isRunning=true baseIntent=Intent { cmp=x/.A } requestedVisibleTypes=8 " +
                "topActivityRequestOpenInBrowserEducationTimestamp=0}"))
        // текст приложения раньше поля не перебивает системное значение
        assertEquals(-9, FrameworkTaskIdentityPolicy.requestedVisibleTypes(
            "TaskInfo{taskDescription=TaskDescription Label: x requestedVisibleTypes=0 requestedVisibleTypes=-9}"))
        assertNull(FrameworkTaskIdentityPolicy.requestedVisibleTypes("TaskInfo{userId=0 taskId=42 displayId=7 isRunning=true}"))
    }

    @Test fun newerFrameworkPrefixWithEffectiveUidIsAccepted() {
        assertEquals(FrameworkTaskIdentityPolicy.Identity(7, 0, 42),
            parse("TaskInfo{userId=0 taskId=42 effectiveUid=10234 displayId=7 isRunning=true baseIntent=Intent { }}"))
        assertNull(parse("TaskInfo{userId=0 taskId=42 effectiveUid=x displayId=7 isRunning=true }"))
        assertNull(parse("TaskInfo{userId=0 taskId=42 effectiveUid=1 effectiveUid=2 displayId=7 isRunning=true }"))
    }

    @Test fun primaryDisplayAndDifferentUserRemainVisibleForLaterScopedFiltering() {
        assertEquals(FrameworkTaskIdentityPolicy.Identity(0, 10, 42),
            parse("TaskInfo{userId=10 taskId=42 displayId=0 isRunning=false}"))
    }

    @Test fun applicationTextCannotOverrideSystemIdentity() {
        assertEquals(FrameworkTaskIdentityPolicy.Identity(7, 0, 42), parse(
            "TaskInfo{userId=0 taskId=42 displayId=7 isRunning=true baseIntent=Intent { " +
                "data=TaskInfo{userId=10 taskId=42 displayId=999 isRunning=true } }}"
        ))
    }

    @Test fun prefixCannotBeFoundInsideApplicationTextOrAnotherWrapper() {
        val valid = "TaskInfo{userId=0 taskId=42 displayId=7 isRunning=true }"
        for (text in listOf(" $valid", "Intent { $valid }", "TaskInfo{baseIntent=$valid}", "\n$valid")) {
            assertNull(text, parse(text))
        }
    }

    @Test fun unknownOrReorderedFormatIsUnknownInsteadOfSearchingLaterFields() {
        for (text in listOf(
            "TaskInfo{taskId=42 userId=0 displayId=7 isRunning=true }",
            "TaskInfo{userId=0 taskId=42 extra=1 displayId=7 isRunning=true }",
            "TaskInfo{userId=0 taskId=42 baseIntent=displayId=7 isRunning=true }",
            "TaskInfo{userId=0 taskId=42 displayId=7 isRunning=trueevil }",
            "TaskInfo{userId=0 taskId=42 displayId=7 isRunning=1 }",
            "TaskInfo{userId=0 taskId=42 displayId=7 isRunning=true"
        )) assertNull(text, parse(text))
    }

    @Test fun publicTaskIdMismatchMakesObservationUnknown() {
        assertNull(parse("TaskInfo{userId=0 taskId=43 displayId=7 isRunning=true }"))
        assertNull(parse("TaskInfo{userId=0 taskId=42 displayId=7 isRunning=true }", -1))
    }

    @Test fun invalidNumbersNeverWrapOrChangeSign() {
        for (number in listOf("-1", "+7", "07", "2147483648", "99999999999", "7.0", "７")) {
            assertNull(number, parse("TaskInfo{userId=0 taskId=42 displayId=$number isRunning=true }"))
            assertNull(number, parse("TaskInfo{userId=$number taskId=42 displayId=7 isRunning=true }"))
        }
        assertNull(parse("TaskInfo{userId=0 taskId=2147483648 displayId=7 isRunning=true }"))
    }

    @Test fun validIntegerBoundaryIsAcceptedWithoutBroadeningSelection() {
        assertEquals(FrameworkTaskIdentityPolicy.Identity(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
            parse("TaskInfo{userId=2147483647 taskId=2147483647 displayId=2147483647 isRunning=false }", Int.MAX_VALUE))
    }

    @Test fun oversizedApplicationSuffixDoesNotAffectBoundedPrefix() {
        assertEquals(FrameworkTaskIdentityPolicy.Identity(7, 0, 42),
            parse("TaskInfo{userId=0 taskId=42 displayId=7 isRunning=true baseIntent=" + "x".repeat(100_000)))
        assertNull(parse("TaskInfo{" + "x".repeat(100_000) + "userId=0 taskId=42 displayId=7 isRunning=true }"))
    }

    @Test fun directlyAccessibleFieldsRejectUnknownIdentities() {
        assertNull(FrameworkTaskIdentityPolicy.fromFields(-1, 0, 42))
        assertNull(FrameworkTaskIdentityPolicy.fromFields(7, -1, 42))
        assertNull(FrameworkTaskIdentityPolicy.fromFields(7, 0, -1))
        assertEquals(FrameworkTaskIdentityPolicy.Identity(7, 0, 42),
            FrameworkTaskIdentityPolicy.fromFields(7, 0, 42))
    }
}
