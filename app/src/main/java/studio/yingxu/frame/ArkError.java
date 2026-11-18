package studio.yingxu.frame;

import org.json.JSONObject;
import java.net.*;
import javax.net.ssl.SSLException;

final class ArkError {
    static String describe(int status, JSONObject error, String trace, String retry) {
        String code=error==null?"":error.optString("code"), hint;
        if(code.contains("Overdue")) hint="账号欠费，请在火山费用中心检查余额。";
        else if(code.contains("SetLimit")||code.contains("Quota")||code.contains("Budget")) hint="用量或预算已达上限，请在方舟控制台检查额度和模型开通状态。";
        else if(code.contains("Sensitive")||code.contains("RiskDetection")) hint="内容审核未通过，请检查提示词和参考图。";
        else if(code.contains("Ipm")) hint="当前模型每分钟图片配额已满。请稍后重试，或减少组图张数。";
        else if(code.contains("ModelNotOpen")||code.contains("NotActivated")) hint="模型尚未开通，请在当前方舟项目中开通服务。";
        else if(status==401) hint="密钥无效或已过期，请重新配置北京地域标准 API Key。";
        else if(status==402) hint="计费状态异常，请检查余额和可用额度。";
        else if(status==403) hint="没有访问权限，请核对密钥所属项目、模型授权与开通状态。";
        else if(status==404) hint="模型或接入点不存在，或密钥无权访问。请同步模型并检查 ID。";
        else if(status==413) hint="请求体过大，请减少参考图数量。";
        else if(status==429) hint="服务限流，请稍后再试。";
        else if(status>=500) hint="方舟服务暂时异常，请保留请求 ID 以核对结果。";
        else hint="请求未被接受，请检查模型、提示词和图片参数。";
        StringBuilder out=new StringBuilder(hint);
        if(status>0)out.append("\nHTTP ").append(status);
        if(!code.isEmpty())out.append(" · ").append(code);
        if(retry!=null && !retry.isEmpty())out.append("\n服务端建议等待（Retry-After）：").append(retry);
        if(trace!=null&&!trace.isEmpty())out.append("\n请求 ID：").append(trace);
        String message=error==null?"":error.optString("message");
        if(!message.isEmpty())out.append("\n").append(message.length()>1400?message.substring(0,1400):message);
        return out.toString();
    }
    static String explain(Exception e) {
        if(e instanceof SocketTimeoutException)return "连接或等待响应超时，请检查网络。";
        if(e instanceof UnknownHostException || e instanceof ConnectException)return "无法连接服务器，请检查网络、DNS 和设备的联网权限。";
        if(e instanceof SSLException)return "安全连接失败，请检查系统时间和网络连接。";
        String msg=e.getMessage();return msg==null?"操作未完成，请重试。":msg;
    }
}
