/**
 * Copyright 2017 Jonas Berlin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.nitor.junit.jmocha;

import org.junit.platform.engine.*;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.hierarchical.HierarchicalTestEngine;
import org.junit.platform.engine.support.hierarchical.Node;

/**
 * Created by xkr47 on 12.5.2017.
 */
public class JMochaTestEngine extends HierarchicalTestEngine<JMochaEngineExecutionContext> {

    public static final String ENGINE_ID = "jmocha";

    @Override
    public String getId() {
        return ENGINE_ID;
    }

    @Override
    protected JMochaEngineExecutionContext createExecutionContext(ExecutionRequest request) {
        return null;
    }

    /*
    static class JMochaTestDescriptor extends AbstractTestDescriptor
            implements Node<JMochaEngineExecutionContext> {

        public JMochaTestDescriptor(UniqueId uniqueId, JMochaTest jmochaTest, TestSource source) {
            super(uniqueId, jmochaTest.getDisplayName());
            this.dynamicTest = dynamicTest;
            setSource(source);
        }

        @Override
        public TestDescriptor.Type getType() {
            return TestDescriptor.Type.TEST;
        }

        @Override
        public JMochaEngineExecutionContext execute(JMochaEngineExecutionContext context,
                                                     Node.DynamicTestExecutor dynamicTestExecutor) throws Exception {
            executeAndMaskThrowable(dynamicTest.getExecutable());
            return context;
        }
    }
*/

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest discoveryRequest, UniqueId uniqueId) {
        JMochaEngineDescriptor engineDescriptor = new JMochaEngineDescriptor(uniqueId);
        TestDescriptor td = null;
        engineDescriptor.addChild(td);
        return engineDescriptor;
    }
}
