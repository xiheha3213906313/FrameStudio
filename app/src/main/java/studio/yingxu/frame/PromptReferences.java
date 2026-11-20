package studio.yingxu.frame;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;

final class PromptReferences {
    static String label(boolean mac, int index) {
        if (mac) return "@image " + index;
        String numerals = "零一二三四五六七八九";
        String number = index < 10 ? numerals.substring(index, index + 1) :
            "十" + (index == 10 ? "" : numerals.substring(index - 10, index - 9));
        return "@图" + number;
    }

    static String serialize(JSONObject body, JSONArray effective, boolean mac) throws Exception {
        String prompt = body.getString("prompt");
        JSONArray bindings = body.optJSONArray("promptRefs");
        if (bindings == null || bindings.length() == 0) return prompt;
        JSONArray displayed = body.getJSONArray("refs");
        StringBuilder output = new StringBuilder();
        int end = 0;
        for (int i = 0; i < bindings.length(); i++) {
            JSONObject binding = bindings.getJSONObject(i);
            int start = binding.getInt("start"), next = binding.getInt("end");
            String ref = binding.getString("ref");
            int shown = indexOf(displayed, ref), actual = indexOf(effective, ref);
            if (shown == 0) throw new IOException("提示词中有已删除的参考图，请修正失效引用。");
            if (actual == 0) throw new IOException("局部编辑只能引用正在修复的底图，请修正其他图片引用。");
            if (shown > 14 || start < end || next <= start || next > prompt.length() ||
                !prompt.substring(start, next).equals(label(mac, shown))) throw new IOException("提示词引用已被改动，请重新插入。");
            output.append(prompt, end, start).append(mac ? "@image " : "图").append(actual);
            end = next;
        }
        return output.append(prompt.substring(end)).toString();
    }

    private static int indexOf(JSONArray refs, String target) {
        for (int i = 0; i < refs.length(); i++) if (target.equals(refs.optString(i))) return i + 1;
        return 0;
    }
}
