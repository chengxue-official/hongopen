package com.houg.tv.sign;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import okhttp3.HttpUrl;

/**
 * 红果 App 接口请求装配：固定 query 参数 + 随机 device_id/iid + _rticket + 签名头。
 *
 * <p><b>普通接口走三件套</b>（{@link MedusaSign}）：x-gorgon + x-helios + x-medusa。
 * 服务端缺任意一个都返回 HTTP 200 但 body 为 0 字节——2026-10-22 复现：
 * 只发 X-Gorgon 时首页分类恒为 {@code 200 len=0}，补齐后同接口返回 53035 字节。
 *
 * <p><b>评论（弹幕）接口</b>走另一套：X-Argus / X-Ladon + 空 X-SS-STUB。
 */
public final class SignedRequest {

    /** 主域名；实际使用时优先走 {@link HostPool}（多域名容灾）。 */
    public static final String BASE_URL = "https://api5-normal-sinfonlineb.fqnovel.com";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 每次启动生成一次并缓存（19 位数字） */
    private static final String DEVICE_ID = random19();
    private static final String IID = random19();

    /** 分支重试上限：命中率 1/3，128 次全不中的概率约 1e-22 */
    private static final int BRANCH_TRIES = 128;

    /** x-gorgon 的 i6 位（原实现从 0..65535 里随机取，这里固定值即可） */
    private static final int GORGON_I6 = 12345;

    public final HttpUrl url;
    public final Map<String, String> headers;
    public final byte[] body;
    /** 本次请求使用的域名（带 https:// 前缀），供调用方做成功/失败上报。 */
    public final String baseUrl;
    /** 本次命中的 x-medusa 分支号（0..5）；-1 表示未走三件套或未命中。 */
    public final int branchIE;

    public SignedRequest(String path, byte[] body) {
        this(path, body, false, null);
    }

    /**
     * @param comment 评论（弹幕）接口走专用签名：X-SS-STUB 置空 + X-Argus/X-Ladon +
     *                Comment-Source/Server-Channel 头（对齐 Go signHongguoCommentRequest）。
     */
    public SignedRequest(String path, byte[] body, boolean comment) {
        this(path, body, comment, null);
    }

    /**
     * @param baseUrl 指定接口域名（如 https://api5-normal-lf.fqnovel.com）；传 null 用 HostPool 当前首选。
     */
    public SignedRequest(String path, byte[] body, boolean comment, String baseUrl) {
        this.body = body == null ? new byte[0] : body;
        String base = (baseUrl == null || baseUrl.isEmpty()) ? HostPool.current() : baseUrl;
        this.baseUrl = base;
        long now = System.currentTimeMillis();
        int ts = (int) (now / 1000L);

        HttpUrl.Builder b = HttpUrl.parse(base + path).newBuilder();
        b.addQueryParameter("aid", "8662");
        b.addQueryParameter("app_name", "novelread");
        b.addQueryParameter("version_code", "73532");
        b.addQueryParameter("version_name", "7.3.5.32");
        b.addQueryParameter("manifest_version_code", "73532");
        b.addQueryParameter("update_version_code", "73532");
        b.addQueryParameter("channel", "update_64");
        b.addQueryParameter("device_platform", "android");
        b.addQueryParameter("os", "android");
        b.addQueryParameter("ssmix", "a");
        b.addQueryParameter("device_type", "25053RT47C");
        b.addQueryParameter("device_brand", "Redmi");
        b.addQueryParameter("language", "zh");
        b.addQueryParameter("os_api", "36");
        b.addQueryParameter("os_version", "16");
        b.addQueryParameter("resolution", "1280*2772");
        b.addQueryParameter("dpi", "520");
        b.addQueryParameter("ac", "wifi");
        b.addQueryParameter("device_id", DEVICE_ID);
        b.addQueryParameter("iid", IID);
        b.addQueryParameter("_rticket", String.valueOf(now));

        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", XGorgon.USER_AGENT);
        h.put("Accept", "application/json");
        h.put("X-XS-From-Web", "0");
        h.put("Sdk-Version", "2");
        h.put("Content-Type", "application/json; charset=utf-8");

        if (comment) {
            HttpUrl built = b.build();
            String rawQuery = built.encodedQuery();

            HongguoCommentSign.Result sign = HongguoCommentSign.sign(rawQuery, now);
            h.put("Comment-Source", "601");
            h.put("Server-Channel", "1000");
            h.put("X-Khronos", sign.xKhronos);
            h.put("X-SS-STUB", "");
            h.put("X-Gorgon", sign.xGorgon);
            h.put("X-SS-Req-Ticket", sign.xReqTicket);
            h.put("X-Argus", sign.xArgus);
            h.put("X-Ladon", sign.xLadon);

            this.branchIE = -1;
            this.url = built;
        } else {
            // 三件套必须成组出现。x-medusa 的轮数/常量表由分支哈希决定，
            // 而分支哈希只接受 (h & 15) % 3 == 0 的 query；不满足时改 _rticket 重算。
            // 注意：query 里**不能**带 ts 参数（带了直接 404），ts 只走 X-Khronos 头。
            HttpUrl built = null;
            String rawQuery = null;
            long ticket = now;
            int hitIE = -1;
            for (int k = 0; k < BRANCH_TRIES; k++) {
                long cand = now + k;
                b.setQueryParameter("_rticket", String.valueOf(cand));
                HttpUrl u = b.build();
                String q = u.encodedQuery();
                int hash = MedusaSign.branch(q, this.body, ts);
                if ((hash & 15) % 3 == 0) {
                    built = u;
                    rawQuery = q;
                    ticket = cand;
                    hitIE = (hash & 15) / 3;
                    break;
                }
            }
            if (built == null) {
                // 理论上走不到；兜底仍按第 0 次发，绝不让构造器抛异常打断 UI
                b.setQueryParameter("_rticket", String.valueOf(now));
                built = b.build();
                rawQuery = built.encodedQuery();
                ticket = now;
            }
            this.branchIE = hitIE;

            h.put("X-Khronos", String.valueOf(ts));
            h.put("X-SS-STUB", XGorgon.ssStub(this.body));
            h.put("X-SS-Req-Ticket", String.valueOf(ticket));
            h.put("X-Gorgon", MedusaSign.xGorgon(rawQuery.getBytes(UTF8), this.body, ts, GORGON_I6));
            h.put("x-helios", MedusaSign.heliosFor(ts));
            h.put("x-medusa", MedusaSign.medusaForQuery(rawQuery, this.body, ts, deviceParams(), null));

            this.url = built;
        }

        this.headers = h;
    }

    public static String deviceId() { return DEVICE_ID; }
    public static String iid() { return IID; }

    /** x-medusa 设备树用到的字段（顺序无关，只做 key 取值） */
    private static Map<String, String> deviceParams() {
        Map<String, String> m = new HashMap<>();
        m.put("device_id", DEVICE_ID);
        m.put("version_name", "7.3.5.32");
        m.put("device_brand", "Redmi");
        m.put("device_type", "25053RT47C");
        m.put("os_version", "16");
        m.put("os_api", "36");
        return m;
    }

    /** 19 位数字：1000000000000000000 + random(8000000000000000000) */
    private static String random19() {
        byte[] r = new byte[8];
        RANDOM.nextBytes(r);
        BigInteger v = new BigInteger(1, r);
        BigInteger span = BigInteger.valueOf(8000000000000000000L);
        BigInteger base = BigInteger.valueOf(1000000000000000000L);
        return base.add(v.mod(span)).toString();
    }
}
