package top.huzile.mcqq.bind;

import java.security.SecureRandom;

/** 生成一次性绑定验证码。 */
public final class CodeGenerator {
	/**
	 * 去掉了容易混淆的字符(0/O、1/I/L),避免玩家在踢出界面看错。
	 */
	private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

	private CodeGenerator() {
	}

	public static String generate(int length, SecureRandom random) {
		StringBuilder builder = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
		}
		return builder.toString();
	}
}
