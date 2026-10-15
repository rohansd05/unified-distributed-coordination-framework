package com.udcf.web;

import com.udcf.modules.matrix.MatrixModule;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;

/**
 * Test support: the real Experiment 10 module (lab 10) as a Mockito spy, so controller tests can make a
 * reset hit a BUSY module ({@code doReturn(ModuleStatus.BUSY).when(module).status()}) and count its resets
 * ({@code verify(module).reset()}). Every other call runs the real module.
 *
 * <p>Importing this class gives the test its own Spring context (context D), separate from every context
 * that relies on startup events. All crash and reset tests live there. Until step E10c this class registered
 * a fake module with lab number 10; once every lab number 1 to 10 belonged to a real module, a fake could no
 * longer join the registry (duplicate lab numbers fail startup), so the real lab 10 is spied instead.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
@SpyBean(MatrixModule.class)
public class TestModuleConfig {
}
