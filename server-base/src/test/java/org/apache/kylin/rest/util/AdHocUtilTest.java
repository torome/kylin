/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package org.apache.kylin.rest.util;

import static org.apache.kylin.metadata.MetadataManager.CCInfo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.kylin.metadata.model.ComputedColumnDesc;
import org.junit.Assert;
import org.junit.Test;

public class AdHocUtilTest {

    @Test
    public void testReplaceIdentifierInExpr() {
        {
            String ret = AdHocUtil.replaceIdentifierInExpr("a.b.x * a.b.y", null, false);
            Assert.assertEquals("b.x * b.y", ret);
        }
        {
            String ret = AdHocUtil.replaceIdentifierInExpr("a_1.b_2.x_3 * a_1.b_2.y_3", null, false);
            Assert.assertEquals("b_2.x_3 * b_2.y_3", ret);
        }
        {
            String ret = AdHocUtil.replaceIdentifierInExpr("a.b.x * a.b.y", "c", false);
            Assert.assertEquals("c.x * c.y", ret);
        }
        {
            String ret = AdHocUtil.replaceIdentifierInExpr("a.b.x * a.b.y", "c", true);
            Assert.assertEquals("\"C\".\"X\" * \"C\".\"Y\"", ret);
        }
        {
            String ret = AdHocUtil.replaceIdentifierInExpr("substr(a.b.x,1,3)>a.b.y", "c", true);
            Assert.assertEquals("substr(\"C\".\"X\",1,3)>\"C\".\"Y\"", ret);
        }
        {
            String ret = AdHocUtil.replaceIdentifierInExpr("strcmp(substr(a.b.x,1,3),a.b.y) > 0", "c", true);
            Assert.assertEquals("strcmp(substr(\"C\".\"X\",1,3),\"C\".\"Y\") > 0", ret);
        }
        {
            String ret = AdHocUtil.replaceIdentifierInExpr("strcmp(substr(a.b.x,1,3),a.b.y) > 0", null, true);
            Assert.assertEquals("strcmp(substr(\"B\".\"X\",1,3),\"B\".\"Y\") > 0", ret);
        }
        {
            String ret = AdHocUtil.replaceIdentifierInExpr("strcmp(substr(a.b.x, 1, 3),a.b.y) > 0", null, false);
            Assert.assertEquals("strcmp(substr(b.x, 1, 3),b.y) > 0", ret);
        }
    }

    @Test
    public void testRestoreComputedColumnToExpr() {

        ComputedColumnDesc computedColumnDesc = mock(ComputedColumnDesc.class);
        when(computedColumnDesc.getColumnName()).thenReturn("DEAL_AMOUNT");
        when(computedColumnDesc.getExpression()).thenReturn("DB.TABLE.price * DB.TABLE.number");

        CCInfo ccInfo = mock(CCInfo.class);
        when(ccInfo.getComputedColumnDesc()).thenReturn(computedColumnDesc);

        {
            String ret = AdHocUtil.restoreComputedColumnToExpr(
                    "select DEAL_AMOUNT from DB.TABLE group by date order by DEAL_AMOUNT", ccInfo);
            Assert.assertEquals(
                    "select (TABLE.price * TABLE.number) from DB.TABLE group by date order by (TABLE.price * TABLE.number)",
                    ret);
        }
        {
            String ret = AdHocUtil.restoreComputedColumnToExpr(
                    "select DEAL_AMOUNT as DEAL_AMOUNT from DB.TABLE group by date order by DEAL_AMOUNT", ccInfo);
            Assert.assertEquals(
                    "select (TABLE.price * TABLE.number) as DEAL_AMOUNT from DB.TABLE group by date order by (TABLE.price * TABLE.number)",
                    ret);
        }
        {
            String ret = AdHocUtil.restoreComputedColumnToExpr(
                    "select \"DEAL_AMOUNT\" AS deal_amount from DB.TABLE group by date order by DEAL_AMOUNT", ccInfo);
            Assert.assertEquals(
                    "select (\"TABLE\".\"PRICE\" * \"TABLE\".\"NUMBER\") AS deal_amount from DB.TABLE group by date order by (TABLE.price * TABLE.number)",
                    ret);
        }
    }
}
