package io.mycat.calcite.sqlfunction.stringfunction;

import com.google.common.collect.ImmutableList;
import io.mycat.calcite.MycatSqlDefinedFunction;
import org.apache.calcite.schema.ScalarFunction;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public class MakeSetFunction extends MycatSqlDefinedFunction {
    public static ScalarFunction scalarFunction = ScalarFunctionImpl.create(MakeSetFunction.class,
            "makeSet");

    public static final MakeSetFunction INSTANCE = new MakeSetFunction();

    public MakeSetFunction() {
        super(new SqlIdentifier("MAKE_SET", SqlParserPos.ZERO),
                ReturnTypes.explicit(SqlTypeName.VARCHAR),
              null,
                OperandTypes.VARIADIC,
                ImmutableList.of(),
                scalarFunction);
    }

    public static String makeSet(Long bits,String... strs) {
        if (bits == null ||strs == null) {
            return null;
        }
        BitSet bitSet = BitSet.valueOf(new long[]{bits});
        int length = bitSet.length();
        List<String> list = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            if (bitSet.get(i)){
                String str = strs[i];
                if (str!=null){
                    list.add(str);
                }
            }
        }
        return String.join(",",list);
    }
}