package com.documenter.vo;

import com.documenter.util.JwtUtil;
import lombok.Data;

/**
 * 双 Token 响应（TODO 3.1）。
 *
 * <p>expiresIn 为 Access Token 剩余秒数，refreshExpiresIn 为 Refresh Token 剩余秒数，
 * 单位统一为秒，前端据此决定何时调用刷新接口。
 */
@Data
public class TokenPairVO {

    private String accessToken;
    private String refreshToken;
    private long expiresIn;
    private long refreshExpiresIn;

    public static TokenPairVO from(JwtUtil.TokenPair pair) {
        TokenPairVO vo = new TokenPairVO();
        vo.setAccessToken(pair.accessToken());
        vo.setRefreshToken(pair.refreshToken());
        vo.setExpiresIn(pair.expiresIn());
        vo.setRefreshExpiresIn(pair.refreshExpiresIn());
        return vo;
    }

    public static TokenPairVO of(String accessToken, String refreshToken, long expiresIn, long refreshExpiresIn) {
        TokenPairVO vo = new TokenPairVO();
        vo.setAccessToken(accessToken);
        vo.setRefreshToken(refreshToken);
        vo.setExpiresIn(expiresIn);
        vo.setRefreshExpiresIn(refreshExpiresIn);
        return vo;
    }
}
