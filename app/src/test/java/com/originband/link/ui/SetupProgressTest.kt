// SPDX-License-Identifier: AGPL-3.0-or-later
package com.originband.link.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupProgressTest {
    @Test
    fun missingRootIsFirstEvenWithBinding() {
        val progress = SetupProgress(false, true, true, false, false, false)
        assertEquals(SetupProgress.Step.ROOT, progress.current())
        assertTrue(progress.showChecklist())
        assertEquals("检查 Root", progress.primaryLabel())
    }

    @Test
    fun missingBindingIsImportEvenWhenUnregistered() {
        val progress = SetupProgress(true, false, false, false, false, false)
        assertEquals(SetupProgress.Step.IMPORT, progress.current())
        assertTrue(progress.showChecklist())
        assertEquals("导入绑定", progress.primaryLabel())
    }

    @Test
    fun importedWithoutProfileGoesToAdd() {
        val progress = SetupProgress(true, true, false, false, false, false)
        assertEquals(SetupProgress.Step.ADD, progress.current())
        assertTrue(progress.showChecklist())
        assertEquals("添加到健康", progress.primaryLabel())
    }

    @Test
    fun registeredWithoutProfileIsDone() {
        val progress = SetupProgress(true, true, false, true, true, true)
        assertEquals(SetupProgress.Step.DONE, progress.current())
        assertFalse(progress.showChecklist())
        assertEquals("立即同步", progress.primaryLabel())
    }

    @Test
    fun importedUnregisteredIsAdd() {
        val progress = SetupProgress(true, true, true, false, false, false)
        assertEquals(SetupProgress.Step.ADD, progress.current())
        assertTrue(progress.showChecklist())
        assertEquals("添加到健康", progress.primaryLabel())
    }

    @Test
    fun profileWithoutNativeIsAdd() {
        val progress = SetupProgress(true, true, true, true, false, true)
        assertEquals(SetupProgress.Step.ADD, progress.current())
        assertEquals("添加到健康", progress.primaryLabel())
    }

    @Test
    fun registeredWithoutAccountIsDone() {
        val progress = SetupProgress(true, true, true, true, true, false)
        assertEquals(SetupProgress.Step.DONE, progress.current())
        assertFalse(progress.showChecklist())
        assertEquals("立即同步", progress.primaryLabel())
    }

    @Test
    fun confirmedAccountHidesChecklist() {
        val progress = SetupProgress(true, true, true, true, true, true)
        assertEquals(SetupProgress.Step.DONE, progress.current())
        assertFalse(progress.showChecklist())
        assertEquals("立即同步", progress.primaryLabel())
    }

    @Test
    fun missingBindingOutranksRegister() {
        val progress = SetupProgress(true, false, true, true, true, false)
        assertEquals(SetupProgress.Step.IMPORT, progress.current())
        assertTrue(progress.showChecklist())
        assertEquals("导入绑定", progress.primaryLabel())
    }
}
