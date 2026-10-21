package app.msime.android;

/** 验证码提示必须超时、一次消费并拒绝非数字值。 */
public final class OtpSuggestionSmoke {
    public static void main(String[] args) {
        OtpSuggestion state=new OtpSuggestion();
        state.offer("482615",1000);
        if (!"482615".equals(state.code(1001))) throw new AssertionError("missing code");
        if (state.code(301000)!=null) throw new AssertionError("expired code");
        state.offer("482615",1000); state.clear();
        if (state.code(1001)!=null) throw new AssertionError("consumed code");
        state.offer("not-a-code",1000);
        if (state.code(1001)!=null) throw new AssertionError("invalid code");
        state.offer("482615",1000);
        if (state.code(999)!=null) throw new AssertionError("backward clock");
        System.out.println("OTP suggestion expiry, dismissal and bounds passed");
    }
}
