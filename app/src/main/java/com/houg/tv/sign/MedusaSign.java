package com.houg.tv.sign;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/**
 * 红果 App 接口「三件套」签名：x-gorgon + x-helios + x-medusa。
 *
 * <p><b>为什么必须三件全发</b>：服务端校验三件套齐备，缺任意一个都返回
 * HTTP 200 但 body 为 0 字节——不报错、不给错误码，极容易被误判成「接口没数据」。
 * 2026-10-22 实测：只发 X-Gorgon 时首页分类接口恒为 {@code 200 len=0}；
 * 补齐 helios/medusa 后同一接口返回 53035 字节真实片单。
 *
 * <p><b>branch 分支</b>：x-medusa 的内部轮数/常量表由请求哈希的低 4 位决定，
 * 而低 4 位必须满足 {@code (h & 15) % 3 == 0}。做法是循环改 {@code _rticket}
 * 重算哈希直到命中（实测 128 次内必命中，命中率 1/3）。
 *
 * <p>逐行移植自 com.hgtv.star v1.2.1 的 {@code E0.G0.l()}（反编译源 E0/G0.java 744-1257）。
 * 原实现从磁盘读 sbox.bin，Android 上改为 1024 字节内联常量。
 */
public final class MedusaSign {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SecureRandom RND = new SecureRandom();

    private MedusaSign() {
    }

    // ==================== 常量 ====================

    /** 1024B = 4 个 256B 变体；下标 {@code (ts & 3) * 256 + i}。内联，不依赖外部文件。 */
    private static final byte[] SBOX = hexDec("fa7d086b9c59b34b045f39d0384a91990067a6209ff54d827326eedf18668333800319fbd9feaeaaa9b052c60bf379254e78b436ac5d1a279e88dbbd3c63ec4915c1301fdcb856d46ccdca0943c835a3ef1ef496d2fc0e727b9484d1ea455a62023fd31281342bdd7ee628f2a54613013b21f66137292a0ded8cafbf9d5cbb24760f75e45389e1988db19a65704f544c58ab6e6f8b23c407110cbacfa0a48ed8053d14b2da74c3d7e7bed67fde48163e8590a155b7774222c986502e17f964312c9bf16d1c4468e3e9a89397cb3257ebe5716aadc0ccc7c5fd601da22d47a7e251695e7ace0a41b6958ff7b987e03a06108ab5f85bd5f0bc92ff7c2fc2e81b40ec1bdabdba98910cb22b83413467fb0ad876b546055961237590872ae350154cacb179ebaee595470468f0863d518b0fca8ee4b94ef21282bc0ed5f7ef2825cf5b5de96a5502e133be93e7f5ad9d3e3924a8e2fa1757d07a0d0830d6b8a38dfd079ac41e6e226497d21db0bf45663f6cdddb2780a711dca6c552f8c0b6c85c0073607ba01913aac935484bd3a4cd9f99f3104440547e29f4061fa2aba12f3cf6af856236217f5edf201ab3b4e6ff72848f6526945a77ea4378c74acc2c146bc6e87453fcd41cce317003188c96383289f13a5fd7f9a969b7633758c23bc371cb9e92018a0b4d889bbb4f6d6fe0fea549de561609ed9cc12dee817dd97cd12e425b4dc1a65dea44fd454e1ba13fd189e17d2faadbabad59cbb1ce9a28c9e0f670394ad7ff30f5ddbc573b118db2ee00b6e61a5a7cf9dec4cd2e80bbb94ca59f8408c66f426cf027e78b3a9c51fb6721754131a7ca20432ab7bfd97af2b5f88c2c23834f8f60a004133714e301c563665c7481df58bd68903dd2b334f419933229d649ae0d4bd8079eac1e2d0b40b872ba761071a8e4561d48fee5c24791da87269d1f886bc098be25099733a385165e7fdc6e54e9f7a9c8e8c377d0822bec02628a920e3eb00f05f3f19678388636183c24cf0ab453cc6165a4c794d5157e6def792235126a8e5206557b46645095e20cedd31703a29b99eb1cfcafd47369fa5ff72c1ebfc8e1f39f76807148aa94ad64fb89c660c332b34dd2e044dd5fa8b1c7682334c96d127fb7eb15bea9d17893a00c92a4d747e38ac270ab26419a79a7d814858fc06f56d08c11b92e3ce29dcf0ede035d463ecd38430f335ad91a656c223bfc30a688ea37a2b48d8e519cd640eef9f884f4ae97e9ca0a456757042f835cd5c5c482b6a391981f4aac96816ecb1b0908af1895497d54edfa16313adab866f5a5f1fe10010674cc63df7c2825f6ceb24f8be5bc8769bb8621070036e70b50599b1ce862581961f2bd275eba1de699423d0d2ab5dc5b29f02d4c537b6a734e3f75ff4ba1351755723920d3b0fdef02ec777ee42bdb90c1059e7ad4526b2413");

    /** G1.f755c —— 64 个 SIMON 类轮常量 */
    private static final int[] C64 = {
            -340373585, 128638146, -818837569, -535742209, 1829502207, 1513137086, -1273309640, 873991864,
            -231478417, -1314552628, -859632185, 1204935505, -715762722, 1890078625, 1464286226, -143719800,
            738688778, -636630320, -71798932, -37433600, 1753874957, -428423514, -1932119157, 440175886,
            -857165717, 1792077067, -1605228777, -596530045, 579197809, -709567066, 1936688720, 1700802668,
            1635574438, -467451522, 172024131, 1414414386, -1998587094, 1902910176, 1279687304, 95566771,
            -506415002, -1163666248, -1372359469, -1019733424, 386070363, -26369176, -256585912, 1309670328,
            1923797261, 650459709, -222502666, -1269467775, 1243971549, -1942912350, -1425327226, 1029569300,
            1326462516, -746892585, 1146027798, -351652911, 1822977902, 2640807, 137919738, 1605596550
    };

    // ==================== 基础工具 ====================

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] be32(int v) {
        return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] le64(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (8 * i));
        return b;
    }

    private static int rd32(byte[] a, int off, boolean le) {
        int b0 = a[off] & 255, b1 = a[off + 1] & 255, b2 = a[off + 2] & 255, b3 = a[off + 3] & 255;
        return le ? (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24))
                : (b3 | (b2 << 8) | (b1 << 16) | (b0 << 24));
    }

    private static long rd64(byte[] a, int off, boolean le) {
        long v = 0;
        if (le) {
            for (int i = 7; i >= 0; i--) v = (v << 8) | (a[off + i] & 255L);
        } else {
            for (int i = 0; i < 8; i++) v = (v << 8) | (a[off + i] & 255L);
        }
        return v;
    }

    private static byte[] cat(byte[]... ps) {
        int n = 0;
        for (byte[] p : ps) n += p.length;
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] p : ps) {
            System.arraycopy(p, 0, r, o, p.length);
            o += p.length;
        }
        return r;
    }

    private static byte[] xorcyc(byte[] a, byte[] b) {
        byte[] r = new byte[a.length];
        for (int i = 0; i < a.length; i++) r[i] = (byte) ((a[i] & 255) ^ (b[i % b.length] & 255));
        return r;
    }

    private static byte[] rev(byte[] a) {
        byte[] r = new byte[a.length];
        for (int i = 0; i < a.length; i++) r[a.length - 1 - i] = a[i];
        return r;
    }

    private static byte[] md5(byte[] d) {
        try {
            return MessageDigest.getInstance("MD5").digest(d);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] sm3(byte[] d) {
        return HongguoSm3.digest(d);
    }

    private static byte[] hexDec(String s) {
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("hex odd length");
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) {
            r[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return r;
    }

    private static final char[] HEXC = "0123456789abcdef".toCharArray();

    /** 小写 hex（等价原实现 String.format("%02x")） */
    private static String hex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 255;
            c[i * 2] = HEXC[v >>> 4];
            c[i * 2 + 1] = HEXC[v & 15];
        }
        return new String(c);
    }

    /** 标准 Base64（等价 java.util.Base64 编码器；API 19 以下也能用） */
    private static String b64(byte[] b) {
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    private static int rnd() {
        return RND.nextInt();
    }

    // ==================== 分支哈希（G0.d / G0.e） ====================

    private static int dh(int i2, byte[] bArr) {
        for (int i3 = 0; i3 < bArr.length; i3++) {
            i2 = (i3 & 1) == 0
                    ? ((i2 << 6) ^ ((i2 >>> 4) ^ i2)) ^ (bArr[i3] & 255)
                    : (((i2 << 12) | (bArr[i3] & 255)) ^ ((i2 >>> 7) ^ i2)) ^ (-1);
        }
        return i2;
    }

    private static int eh(byte[] b, int ts, byte[] bodyMd5) {
        int v = dh(dh(539167016, b), bodyMd5);
        return dh(v, le32(ts));
    }

    // ==================== protobuf 编码（G0.i / A1） ====================

    private static final class F {
        final int tag;
        final Object v;
        final String t;

        F(int tag, Object v, String t) {
            this.tag = tag;
            this.v = v;
            this.t = t;
        }
    }

    private static F sint(int tag, long v) {
        return new F(tag, v, "sint");
    }

    private static F str(int tag, String v) {
        return new F(tag, v, "string");
    }

    private static F bytes(int tag, byte[] v) {
        return new F(tag, v, "string");
    }

    private static F f32(int tag, double v) {
        return new F(tag, v, "float");
    }

    private static byte[] vint(long j) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        while (j > 127) {
            o.write((int) ((127 & j) | 128));
            j >>>= 7;
        }
        o.write((int) j);
        return o.toByteArray();
    }

    private static byte[] pb(F... fs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (F f : fs) {
            if (f.t.equals("sint")) {
                long v = ((Number) f.v).longValue();
                byte[] a = vint(((long) f.tag) << 3);
                byte[] b = vint(v < 0 ? ((-v) * 2) - 1 : 2 * v);
                o.write(a, 0, a.length);
                o.write(b, 0, b.length);
            } else if (f.t.equals("float")) {
                byte[] a = vint((((long) f.tag) << 3) | 5);
                o.write(a, 0, a.length);
                byte[] b = le32(Float.floatToIntBits(((Number) f.v).floatValue()));
                o.write(b, 0, b.length);
            } else {
                byte[] b = (f.v instanceof byte[]) ? (byte[]) f.v : f.v.toString().getBytes(UTF8);
                if (b.length != 0) {
                    byte[] a = vint((((long) f.tag) << 3) | 2);
                    byte[] l = vint(b.length);
                    o.write(a, 0, a.length);
                    o.write(l, 0, l.length);
                    o.write(b, 0, b.length);
                }
            }
        }
        return o.toByteArray();
    }

    // ==================== 白盒分组密码（E0.C0134m，SM4 变体） ====================

    private static final class C4 {
        final int[] sbox = new int[256];
        final int[] perm;
        final int[] mix;
        final int seed;
        final int[][] rk = new int[12][4];

        C4(int ts, byte[] iv) {
            int k = ts & 3;
            for (int i = 0; i < 256; i++) sbox[i] = SBOX[k * 256 + i] & 255;
            perm = new int[][]{{1, 0, 2, 3}, {2, 0, 3, 1}, {0, 1, 3, 2}, {1, 0, 2, 3}}[k];
            mix = new int[][]{
                    {0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3},
                    {0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11},
                    {0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15},
                    {0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3}}[k];
            seed = new int[]{-905814564, -2109881018, -918420093, -1030217121}[k];

            byte[] s3 = le32(seed);
            byte[] m12 = cat(s3, s3, s3);
            byte[] m16 = cat(m12, s3);
            byte[] m2 = cat(xorcyc(m16, iv), new byte[32]);

            int[] a = new int[m2.length];
            for (int i = 0; i < a.length; i++) a[i] = m2[i] & 255;

            int i8 = 8;
            for (int i9 = 4; i9 < 12; i9++) {
                int i10 = (i9 - 1) * 4;
                int i11 = a[i10];
                int i12 = a[i10 + 1];
                int i13 = a[i10 + 2];
                int i14 = a[i10 + 3];
                if ((i9 & 3) == 0) {
                    int t2 = (sbox[i12] ^ (seed >> (i8 & 24))) & 255;
                    int t3 = sbox[i13];
                    int t4 = sbox[i14];
                    i14 = sbox[i11];
                    i11 = t2;
                    i12 = t3;
                    i13 = t4;
                }
                i8 += 2;
                a[i10 + 4] = i11 ^ a[i10 - 12];
                a[i10 + 5] = i12 ^ a[i10 - 11];
                a[i10 + 6] = a[i10 - 10] ^ i13;
                a[i10 + 7] = a[i10 - 9] ^ i14;
            }
            for (int r = 0; r < 12; r++) {
                for (int c = 0; c < 4; c++) rk[r][c] = a[r * 4 + c];
            }
        }

        int xt(int v) {
            return (((v & 128) != 0 ? 27 : 0) ^ (v << 1)) & 255;
        }

        void addKey(int[][] s, int off) {
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) s[r][c] ^= rk[off + r][perm[c]];
            }
        }

        byte[] enc(byte[] in) {
            int[][] s = new int[4][4];
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) s[r][c] = in[r * 4 + c] & 255;
            }

            addKey(s, 0);
            for (int rnd = 1; rnd < 3; rnd++) {
                for (int r = 0; r < 4; r++) {
                    for (int c = 0; c < 4; c++) s[r][c] = sbox[s[r][c]];
                }

                int[][] cp = new int[4][];
                for (int r = 0; r < 4; r++) cp[r] = s[r].clone();
                for (int r = 0; r < 4; r++) s[r] = cp[perm[r]].clone();

                int[] flat = new int[16];
                for (int r = 0; r < 4; r++) {
                    for (int c = 0; c < 4; c++) flat[r * 4 + c] = s[r][c];
                }
                for (int i = 0; i < 16; i++) s[i / 4][i % 4] = flat[mix[i]];

                if (rnd == 1) {
                    for (int r = 0; r < 4; r++) {
                        int[] row = s[r].clone();
                        int[] nr = new int[4];
                        for (int c = 0; c < 4; c++) nr[c] = row[perm[c]];
                        s[r] = nr;
                    }
                    for (int c = 0; c < 4; c++) {
                        int t0 = s[0][c];
                        int t1 = s[1][c] ^ t0;
                        int t2 = (s[2][c] ^ t1) ^ s[3][c];
                        s[0][c] = xt(t1) ^ (t0 ^ t2);
                        int u1 = s[1][c];
                        s[1][c] = xt(u1 ^ s[2][c]) ^ (u1 ^ t2);
                        int u2 = s[2][c];
                        s[2][c] = xt(u2 ^ s[3][c]) ^ (u2 ^ t2);
                        int u3 = s[3][c];
                        s[3][c] = xt(t0 ^ u3) ^ (u3 ^ t2);
                    }
                }
                addKey(s, rnd * 4);
            }
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) s[r][c] ^= rk[4 + r][c];
            }

            byte[] out = new byte[16];
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) out[r * 4 + c] = (byte) s[r][c];
            }
            return out;
        }

        /** 位抽取 248B→32B，CBC 两段加密后回写 */
        byte[] f(byte[] in, byte[] iv) {
            if (in.length < 248) throw new IllegalArgumentException("need >=248, got " + in.length);
            byte[] k = new byte[32];
            for (int i = 0; i < 31; i++) {
                int o = i * 8;
                k[i] = (byte) ((((in[o + 7] & 255) >> 4) & 8)
                        | (16 & ((in[o + 5] & 255) << 3))
                        | (((in[o + 4] & 255) >> 1) & 4)
                        | (((in[o] & 255) >> 4) & 2)
                        | (in[o + 1] & 64)
                        | (((in[o + 2] & 255) >> 2) & 1)
                        | (((in[o + 3] & 255) << 3) & 128)
                        | (((in[o + 6] & 255) << 5) & 32));
            }
            k[31] = 1;

            byte[] b1 = enc(xorcyc(Arrays.copyOfRange(k, 0, 16), iv));
            byte[] m = cat(b1, enc(xorcyc(Arrays.copyOfRange(k, 16, 32), b1)));

            byte[] out = in.clone();
            for (int i = 0; i < 31; i++) {
                int o = i * 8;
                byte b2 = m[i];
                int v = b2 & 255;
                int v4 = v << 4;
                out[o] = (byte) ((out[o] & 223) | (v4 & 32));
                out[o + 1] = (byte) ((b2 & 64) | (out[o + 1] & 191));
                out[o + 2] = (byte) ((out[o + 2] & 251) | ((v << 2) & 4));
                out[o + 3] = (byte) ((out[o + 3] & 239) | ((v >> 3) & 16));
                out[o + 4] = (byte) ((out[o + 4] & 247) | ((v + v) & 8));
                out[o + 5] = (byte) ((out[o + 5] & 253) | ((v >> 3) & 2));
                out[o + 6] = (byte) (((v >> 5) & 1) | (out[o + 6] & 254));
                out[o + 7] = (byte) ((out[o + 7] & 127) | (v4 & 128));
            }
            return cat(new byte[]{m[m.length - 1]}, out);
        }
    }

    // ==================== 三个头 ====================

    /** x-gorgon（G0.java 804-868） */
    public static String xGorgon(byte[] query, byte[] body, int ts, int i6) {
        byte[] q4 = Arrays.copyOf(md5(query), 4);
        byte[] b4 = body != null ? Arrays.copyOf(md5(body), 4) : new byte[4];
        byte[] m = cat(q4, b4, new byte[4], le32(67503104), be32(ts));

        int i7 = i6 >> 8;
        int[] key = {74, 64, 22, i7 & 255, 71, 108, 1, i6 & 255};
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            int t = (j + s[i] + key[i % 8]) & 255;
            s[i] = s[t];
            j = t;
        }
        byte[] o = new byte[m.length];
        int acc = 0;
        for (int i = 0; i < m.length; i++) {
            int i15 = i + 1;
            int i17 = acc + s[i15];
            int i18 = s[i17 & 255];
            s[i15] = i18;
            o[i] = (byte) ((m[i] & 255) ^ s[(i18 + i18) & 255]);
            acc = i17;
        }
        for (int i = 0; i < o.length; i++) {
            int v = o[i] & 255;
            int nb = ((v >> 4) | (v << 4)) & 255;
            int nx = (i + 1 < o.length) ? i + 1 : 0;
            o[i] = (byte) ((Integer.reverse(((o[nx] & 255) ^ nb) & 255) >>> 24) ^ (-21));
        }
        return hex(cat(hexDec("8404"), new byte[]{(byte) i6, (byte) i7, 64, 1}, o));
    }

    /** x-helios（G0.java 869-944） */
    private static String xHelios(int ts, int ij) {
        byte[] a = le32(ij);
        byte[] hb = hex(md5(cat(a, "8662".getBytes(UTF8)))).getBytes(UTF8);

        ArrayList<Long> list = new ArrayList<Long>();
        for (int i = 0; i < 4; i++) list.add(rd64(hb, i * 8, true));

        ArrayList<Long> ks = new ArrayList<Long>();
        ks.add(list.get(0));
        long v2 = list.remove(0);
        long v3 = list.remove(0);
        for (int r = 0; r < 34; r++) {
            long j7 = v2;
            long rr = (Long.rotateRight(v3, 8) + j7) ^ r;
            list.add(rr);
            v2 = rr ^ Long.rotateRight(j7, 61);
            ks.add(v2);
            v3 = list.remove(0);
        }

        byte[] msg = (ts + "-1588093228-8662").getBytes(UTF8);
        int pad = 16 - (msg.length % 16);
        byte[] pv = new byte[pad];
        Arrays.fill(pv, (byte) pad);
        byte[] mb = cat(msg, pv);

        byte[] out = le32(ij);
        int last = ((mb.length - 1) / 16) * 16;
        for (int off = 0; ; off += 16) {
            long x0 = rd64(mb, off, true);
            long x1 = rd64(mb, off + 8, true);
            for (int r = 0; r < 34; r++) {
                x1 = ks.get(r) ^ (Long.rotateRight(x1, 8) + x0);
                x0 = Long.rotateRight(x0, 61) ^ x1;
            }
            out = cat(out, le64(x0), le64(x1));
            if (off == last) break;
        }
        return b64(out);
    }

    /** x-medusa（G0.java 946-1233） */
    private static String xMedusa(Map<String, String> dev, byte[] body, int ts, int iE,
                                  byte[] queryBytes, byte[] sm3Query) {
        byte[] bodyMd5 = body != null ? md5(body) : new byte[16];

        int i28 = new int[]{-995654272, -1279197895, -980658554, -911590489, -173560334, -1774440751}[iE];
        int i29 = i28 + ts;

        ArrayList<Integer> K = new ArrayList<Integer>();
        for (int idx = 0; idx < C64.length; idx++) K.add(Integer.rotateRight(C64[idx], (i29 + 1) & 255));

        int i31 = (i29 + 2) & 7;
        int[] iArr4 = {250, 69, 97, 215};
        byte[] c1 = new byte[4];
        for (int i = 0; i < 4; i++) c1[i] = (byte) ((iArr4[i] | (iArr4[i] << 8)) >> i31);

        byte[] m5 = cat(sm3Query, bodyMd5, le32(ts), c1, hexDec("00000000000001a0"));
        ArrayList<Integer> W = new ArrayList<Integer>();
        for (int i = 0; i < m5.length; i += 4) W.add(rd32(m5, i, false));
        for (int i = 0; i < 112; i++) {
            int w1 = W.get(i + 1);
            int w14 = W.get(i + 14);
            W.add(W.get(i + 9) + W.get(i)
                    + ((Integer.rotateLeft(w1, 14) ^ Integer.rotateLeft(w1, 25)) ^ (w1 >>> 3))
                    + ((Integer.rotateLeft(w14, 13) ^ Integer.rotateLeft(w14, 15)) ^ (w14 >>> 10)));
        }

        int[] iArr5 = {2059030472, 1729520903, 1677982208, 873419055, 984304912,
                -1289919430, -1420842003, -2142554032};
        ArrayList<Integer> H = new ArrayList<Integer>();
        for (int idx = 0; idx < iArr5.length; idx++) H.add(Integer.rotateRight(iArr5[idx], i29));

        int[] iArr6 = new int[][]{
                {101, 5, 7, 6, 3, 2, 1, 0, 5, 4, 3},
                {96, 0, 6, 7, 5, 3, 2, 1, 5, 4, 4},
                {96, 7, 6, 2, 1, 4, 0, 5, 4, 3, 5},
                {99, 3, 6, 2, 4, 5, 1, 0, 0, 7, 6},
                {96, 0, 5, 6, 7, 3, 1, 2, 5, 4, 4},
                {100, 2, 0, 3, 5, 4, 6, 7, 2, 1, 5}}[iE];

        ArrayList<Integer> S = new ArrayList<Integer>(H);
        int rounds = iArr6[0];
        for (int r = 0; r < rounds; r++) {
            int idx = i28 + r;
            int a = S.get(iArr6[1]);
            int e = S.get(iArr6[2]);
            int t = S.get(iArr6[5]) + K.get(idx & 63) + W.get(idx & 127)
                    + (((S.get(iArr6[4]) ^ S.get(iArr6[3])) & a) ^ S.get(iArr6[3]))
                    + ((Integer.rotateLeft(a, 26) ^ Integer.rotateLeft(a, 21)) ^ Integer.rotateLeft(a, 7));
            int sig0 = (Integer.rotateLeft(e, 30) ^ Integer.rotateLeft(e, 19)) ^ Integer.rotateLeft(e, 10);
            int maj = (S.get(iArr6[6]) & e) | ((S.get(iArr6[6]) | e) & S.get(iArr6[7]));
            int old = S.get(iArr6[9]);
            S.add(0, S.remove(7));
            S.set(iArr6[10], sig0 + maj + t);
            S.set(iArr6[8], old + t);
        }

        byte[] dig = new byte[0];
        for (int i = 0; i < 8; i++) dig = cat(dig, be32(S.get(i) + H.get(i)));
        byte[] z = xorcyc(Arrays.copyOfRange(dig, 0, 16), Arrays.copyOfRange(dig, 16, 32));

        int i43 = 539100192;
        for (int i44 = 0; i44 < 12; i44++) {
            int odd = i44 & 1;
            i43 = ((odd == 0 ? (i43 << 7) ^ (z[i44] & 255) : ((i43 << 11) | (z[i44] & 255)) ^ (-1))
                    ^ ((i43 >>> (odd == 0 ? 3 : 5)) ^ i43));
        }
        byte[] m7 = cat(z, le32((i43 | 4) ^ 16777216));

        String ver = dev.containsKey("version_name") ? dev.get("version_name") : "7.1.3.32";
        String did = dev.containsKey("device_id") ? dev.get("device_id") : "";
        String brand = dev.containsKey("device_brand") ? dev.get("device_brand") : "Redmi";
        String devType = dev.containsKey("device_type") ? dev.get("device_type") : "25053RT47C";
        String osVer = dev.containsKey("os_version") ? dev.get("os_version") : "16";
        int osApi = 36;
        try {
            osApi = Integer.parseInt(dev.get("os_api"));
        } catch (Exception ignore) {
        }
        String mfr = ("samsung".equalsIgnoreCase(brand)) ? "Samsung"
                : (brand.toLowerCase().contains("redmi") || brand.toLowerCase().contains("xiaomi")) ? "Xiaomi" : brand;

        int jj = rnd();
        long j8 = ((long) jj) & 4294967295L;

        double d1 = Math.random(), d2 = Math.random();
        int i46 = (int) (21 * d1) + 100;
        int i47 = (int) (2000 * d2) + 10001;

        byte[] inner12 = pb(
                sint(1, 1), sint(2, 2), str(3, "8662"), str(4, did),
                str(5, "Ai6svO3PyrwDOUSmO6ZcResxu"), str(6, "!noperm!"),
                sint(7, -888888), sint(8, -888888), sint(9, 3), sint(10, -888888),
                str(11, "!notset!"), str(12, "Asia/Shanghai,8"), str(13, "zh_CN"), sint(14, 4),
                f32(16, 255.24993896484375), f32(17, 35.58599090576172),
                f32(18, 3.467449188232422), f32(19, 3.467449188232422),
                f32(20, 255.1754913330078), f32(21, 42.17544174194336),
                str(22, osVer), sint(23, 41), sint(24, osApi),
                sint(25, 1728388016635L), sint(26, 1728388016635L), sint(27, 1728388016635L),
                sint(28, 1728388016637L), sint(29, -1),
                str(30, devType), str(31, brand), str(32, devType), str(33, devType),
                str(34, mfr), str(35, brand), str(36, brand), sint(38, 31));

        byte[] inner13 = pb(
                sint(1, System.currentTimeMillis() / 1000L), sint(2, -2), sint(4, 200));

        byte[] I15 = pb(sint(1, 111), sint(2, 10), sint(3, 694367), sint(5, 586952199));

        byte[] I3 = pb(
                bytes(1, hexDec("f7e85ffad7d7dc3bd62ac87057cf6118")),
                sint(2, 3),
                sint(3, j8),
                str(4, "8662"),
                str(5, did),
                str(6, "1588093228"),
                str(7, ver),
                str(8, "v04.06.04-ml-android"),
                sint(9, 67503104),
                bytes(10, hexDec("4001000000000000")),
                sint(12, ts),
                bytes(13, m7),
                bytes(14, Arrays.copyOf(sm3Query, 6)),
                bytes(15, I15),
                str(16, "AXYQOS6n2m60x1fVZHIrH3iol"),
                sint(17, ts),
                bytes(19, sm3(cat(queryBytes, bodyMd5, "none".getBytes(UTF8)))),
                str(20, "none"),
                sint(21, 312),
                bytes(23, pb(
                        sint(1, i46), sint(2, 146331399), sint(3, 146331396), sint(5, 7),
                        str(6, "v04.06.04.03-bugfix"), sint(7, i47),
                        bytes(12, inner12), bytes(13, inner13), str(14, ver))),
                str(24, "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":1879194040,\"vpn\":0,\"kd\":0,"
                        + "\"fkd\":3672518972,\"pd\":-1872573247,\"dyn\":\"\",\"do\":0,\"tk\":true}"));

        int iJ2 = rnd();
        byte[] wl = hexDec("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5");
        byte[] x4 = sm3(cat(wl, le32(iJ2), wl));
        int i48 = iJ2 >> 16;
        int i49 = i48 & 255;
        byte[] o8 = le32((i49 ^ (((i49 << 11) | (iJ2 >>> 24)) ^ (i49 >> 5))) ^ (-1));

        int len = I3.length;
        int[] arr = new int[len];
        for (int i = 0; i < len; i++) {
            int off = (i * 4) & 28;
            int a = x4[off] & 255;
            int b = x4[off + 1] & 255;
            int v = I3[i] & 255;
            int t = (((((v << 4) | (v >>> 4)) & 255) + a) ^ (-1) ^ b) & 255;
            arr[len - i - 1] = ((((((t << 3) | (t >>> 5)) & 255) + b) & 255) ^ a ^ (-1)) & 255;
        }

        int i56 = len - 2;
        int lastV = arr[len - 1];
        int iL0 = lastV ^ arr[i56];
        int a0 = arr[0];
        arr[0] = ((iL0 ^ (-1)) + a0) & 255;
        int iL1 = (((arr[0] ^ lastV) ^ 254) + arr[1]) & 255;
        arr[1] = iL1;
        arr[2] = (arr[2] + ((iL0 - a0) ^ (((iL1 << 3) | (iL1 >>> 5)) & 255) ^ 2)) & 255;
        for (int i = 0; i < len - 4; i++) {
            int c = arr[i + 2];
            arr[i + 3] = (((((((c >>> 5) | (c << 3)) & 255) ^ arr[i + 1]) ^ (i + 3)) ^ (-1)) + arr[i + 3]) & 255;
        }
        arr[len - 1] = arr[i56] ^ arr[len - 1];
        int d0 = arr[0] ^ arr[1];
        int sum = 0;
        // 求和排除 arr[0]
        for (int i = 1; i < len; i++) sum += arr[i];
        arr[0] = (sum + d0) & 255;

        byte[] ob = new byte[len];
        for (int i = 0; i < len; i++) ob[i] = (byte) arr[i];

        byte[] n0 = rev(cat(hexDec("4001000000000000"), ob));
        for (int i = 0; i < n0.length; i++) n0[i] = (byte) ((n0[i] & 255) ^ (o8[(i ^ (-1)) & 3] & 255));

        int i67 = ((sm3Query[0] & 63) << 14) | 402653185 | ((m7[0] & 63) << 8);

        byte[] c4in = cat(new byte[]{53}, le32(rnd()), le32(i67), n0,
                new byte[]{(byte) i48, (byte) (iJ2 >> 24)});
        byte[] c4out = new C4(ts, hexDec("f1593376766ea98d34f31b057a9d5be4"))
                .f(c4in, hexDec("1fe109a4125283f418de9e051a969e12"));

        byte[] l3 = hexDec("03000000f7e85ffad7d7dc3bd62ac87057cf6118");
        byte[] pre = new byte[0];
        for (int i = 0; i < 20; i += 4) pre = cat(pre, le32(rd32(l3, i, true) ^ ts));

        return b64(cat(pre, new byte[]{(byte) iJ2, (byte) (iJ2 >> 8), 0, 1}, c4out));
    }

    // ==================== 对外 API ====================

    /**
     * 分支判定：低 4 位必须满足 {@code (h & 15) % 3 == 0}，否则 x-medusa 的
     * 轮数/常量表会与服务端不一致，请求被判为无效（200 + 0 字节）。
     */
    public static int branch(String query, byte[] body, int ts) {
        byte[] qs = sm3(query.getBytes(UTF8));
        byte[] bm = body != null ? md5(body) : new byte[16];
        return eh(qs, ts, bm);
    }

    /** 随机挑战串 x-helios */
    public static String heliosFor(int ts) {
        return xHelios(ts, rnd());
    }

    /**
     * 给定已定稿 query（含 {@code _rticket}，不含 {@code ts}）计算 x-medusa。
     *
     * @param outIE 非空时回填本次命中的分支号（0..5），便于日志排查
     */
    public static String medusaForQuery(String query, byte[] body, int ts,
                                        Map<String, String> dev, int[] outIE) {
        byte[] qb = query.getBytes(UTF8);
        byte[] qs = sm3(qb);
        byte[] bm = body != null ? md5(body) : new byte[16];
        int h = eh(qs, ts, bm);
        int iE = (h & 15) / 3;
        if (outIE != null && outIE.length > 0) outIE[0] = iE;
        return xMedusa(dev, body, ts, iE, qb, qs);
    }
}
