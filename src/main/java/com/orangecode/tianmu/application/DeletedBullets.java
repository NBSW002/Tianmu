package com.orangecode.tianmu.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/** Retains an event fingerprint after moderation without retaining deleted text. */
public final class DeletedBullets {
    private DeletedBullets() {}
    public static String fingerprint(long video,long user,String content,String color,double position) {
        var encoder=Base64.getEncoder();
        String text=encoder.encodeToString(content.getBytes(StandardCharsets.UTF_8));
        String encodedColor=color==null?"null":encoder.encodeToString(color.getBytes(StandardCharsets.UTF_8));
        String payload=video+"|"+user+"|"+text+"|"+encodedColor+"|"+Double.toHexString(position);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Event fingerprint unavailable",unavailable);
        }
    }
}
