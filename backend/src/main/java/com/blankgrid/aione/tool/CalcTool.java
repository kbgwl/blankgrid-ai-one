package com.blankgrid.aione.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 精确四则运算。
 *
 * <p>模型算 "¥89.9 一箱 5 包,买 100 包要几箱、共多少钱" 这类题经常出错 ——
 * 不是它不会,是它在生成文字时不保证算术正确。而采购单上的数字错了是要赔钱的,
 * 所以宁可多一次工具调用,也不要它心算。
 *
 * <p>用 BigDecimal + 递归下降,不走 double:0.1+0.2 在浮点里是 0.30000000000000004,
 * 这种结果贴在报价单上没法看。
 */
@Component
public class CalcTool implements AiTool {

    @Override
    public String name() {
        return "calc";
    }

    @Override
    public String description() {
        return """
                精确计算四则运算。支持 + - * / ( ) 和百分号,例:"89.9/5*100"、"1200*0.87"、"100*(1-3%)"。
                采购比价里所有总价、单价、税费、折扣的计算都必须用它,不要自己心算 ——
                报价单上的数字算错是事故。参数:expression(必填)。""";
    }

    @Override
    public String paramsSchema() {
        return """
                {"type":"object","properties":{
                  "expression":{"type":"string","description":"算术表达式,如 89.9/5*100"}
                },"required":["expression"]}""";
    }

    @Override
    public String category() {
        return "utility";
    }

    /** 纯计算,不改任何状态,决策层可以直接用来核对数字 */
    @Override
    public boolean decisionSafe() {
        return true;
    }

    private static final ObjectMapper M = new ObjectMapper();

    @Override
    public String execute(String argsJson, ToolContext ctx) throws Exception {
        // 用 Jackson 解析而不是手撸字符串截取:表达式中可能带引号或反斜杠,
        // 手工找引号迟早会在这里栽一次。
        String expr = "";
        if (argsJson != null && !argsJson.isBlank()) {
            JsonNode args = M.readTree(argsJson);
            expr = args.path("expression").asText("");
        }
        expr = expr.replace(",", "").replace("，", "").trim();
        if (expr.isEmpty()) return "缺少 expression 参数,例如 {\"expression\":\"89.9/5*100\"}";
        try {
            BigDecimal v = new Parser(expr).parse();
            String plain = v.stripTrailingZeros().toPlainString();
            // 金额习惯保留两位;整数就不带小数
            String pretty = v.scale() > 2
                    ? v.setScale(2, RoundingMode.HALF_UP).toPlainString()
                    : plain;
            return expr + " = " + pretty + (v.scale() > 2 ? "(保留两位小数)" : "");
        } catch (Exception e) {
            return "表达式无法计算:" + e.getMessage() + "。只支持数字与 + - * / ( ) 和 %。";
        }
    }

    static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        BigDecimal parse() {
            BigDecimal v = expr();
            skip();
            if (i < s.length()) throw new IllegalArgumentException("多余字符 '" + s.charAt(i) + "'");
            return v;
        }

        private BigDecimal expr() {
            BigDecimal v = term();
            while (true) {
                skip();
                if (eat('+')) v = v.add(term());
                else if (eat('-')) v = v.subtract(term());
                else return v;
            }
        }

        private BigDecimal term() {
            BigDecimal v = factor();
            while (true) {
                skip();
                if (eat('*')) v = v.multiply(factor());
                else if (eat('/')) {
                    BigDecimal d = factor();
                    if (d.compareTo(BigDecimal.ZERO) == 0) throw new IllegalArgumentException("除数为 0");
                    v = v.divide(d, new MathContext(16, RoundingMode.HALF_UP));
                } else return v;
            }
        }

        private BigDecimal factor() {
            skip();
            if (eat('(')) {
                BigDecimal v = expr();
                skip();
                if (!eat(')')) throw new IllegalArgumentException("括号没闭合");
                return v;
            }
            if (eat('-')) return factor().negate();
            if (eat('+')) return factor();
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (start == i) throw new IllegalArgumentException(i >= s.length() ? "表达式不完整" : "无法识别 '" + s.charAt(i) + "'");
            BigDecimal v = new BigDecimal(s.substring(start, i));
            skip();
            // 百分号:紧跟在数字后面,换算成 /100
            if (eat('%')) v = v.divide(new BigDecimal("100"), new MathContext(16, RoundingMode.HALF_UP));
            return v;
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private boolean eat(char c) {
            skip();
            if (i < s.length() && s.charAt(i) == c) { i++; return true; }
            return false;
        }
    }
}
