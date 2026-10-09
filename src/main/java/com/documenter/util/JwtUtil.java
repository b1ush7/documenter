package com.documenter.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.documenter.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import com.auth0.jwt.algorithms.Algorithm;

@Component
public class JwtUtil {

	@Value("${res.jwt.authorization-name}")
	private String authorizationName;

	@Value("${res.jwt.secret-key}")
	private String secretKey;

	@Value("${res.jwt.key}")
	private String key;

	private RSAPublicKey rsaPublicKey;
	private RSAPrivateKey rsaPrivateKey;

	@PostConstruct
	public void init() throws NoSuchAlgorithmException {
		KeyPair keyPair = generateKeyPair();
		this.rsaPublicKey = (RSAPublicKey) keyPair.getPublic();
		this.rsaPrivateKey = (RSAPrivateKey) keyPair.getPrivate();
	}

	private KeyPair generateKeyPair() throws NoSuchAlgorithmException {
		KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
		keyPairGenerator.initialize(2048);
		return keyPairGenerator.generateKeyPair();
	}

	public String createToken(String openid) {
		Algorithm algorithm = Algorithm.HMAC256(secretKey);
		return JWT.create()
				.withClaim(key, openid)
				.withIssuedAt(new Date())
				.withIssuer("perfume_store")
				.sign(algorithm);
	}

	public DecodedJWT verify(String token) {
		Algorithm algorithm = Algorithm.HMAC256(secretKey);
		JWTVerifier verifier = JWT.require(algorithm)
				.withIssuer("perfume_store")
				.build();

		try {
			return verifier.verify(token);
		} catch (JWTVerificationException e) {
			throw new BusinessException(401,"未授权访问");
		}
	}

	public String getOpenid(String token) {
		DecodedJWT decodedJWT = verify(token);
		return decodedJWT.getClaim(key).asString();
	}

	public boolean validateToken(String token) {
		DecodedJWT decodedJWT = verify(token);
		return decodedJWT!=null;
	}

}
