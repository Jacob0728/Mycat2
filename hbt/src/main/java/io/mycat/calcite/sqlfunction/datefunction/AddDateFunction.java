/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mycat.calcite.sqlfunction.datefunction;

import com.google.common.collect.ImmutableList;
import io.mycat.calcite.MycatSqlDefinedFunction;
import org.apache.calcite.schema.ScalarFunction;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;


public class AddDateFunction extends MycatSqlDefinedFunction {
    public static ScalarFunction scalarFunction = ScalarFunctionImpl.create(AddDateFunction.class,
            "addDate");
    public static AddDateFunction INSTANCE = new AddDateFunction();


    public AddDateFunction() {
        super(new SqlIdentifier("ADDDATE", SqlParserPos.ZERO),
                ReturnTypes.explicit(SqlTypeName.DATE),
                InferTypes.explicit(getRelDataType(scalarFunction)),
                OperandTypes.family(SqlTypeFamily.DATE,SqlTypeFamily.INTEGER),
                ImmutableList.of(), scalarFunction);
    }
    //SqlParserUtil
    //DateTimeUtils
    //SqlLiteral
    public static Integer addDate(Integer date, Integer days) {
        if (date==null||days == null){
            return null;
        }
       return  (int)LocalDate.ofEpochDay(date).plus(days, ChronoUnit.DAYS).toEpochDay();
    }

}

