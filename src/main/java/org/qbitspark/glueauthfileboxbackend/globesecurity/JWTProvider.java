package org.qbitspark.glueauthfileboxbackend.globesecurity;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Service.ApiKeyService;
import org.qbitspark.glueauthfileboxbackend.authentication_service.Service.IMPL.ApiKeyUsageService;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.*;
import org.qbitspark.glueauthfileboxbackend.globeadvice.exceptions.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
public class JWTProvider {
    @Value("${app.jwt-secret}")
    private String secret_key;
    @Value("${app.jwt-expiration-milliseconds}")
    private Long accessTokenExpirationMillis;

    @Value("${app.jwt-refresh-token.expiration-days}")
    private Long refreshTokenExpirationDays;

    private final ApiKeyUsageService apiKeyService;

    public JWTProvider(ApiKeyUsageService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }


    public String generateRefreshToken(Authentication authentication) {
        String userName = authentication.getName();

        // Set expiration to 1 year
        long oneYearInMillis = refreshTokenExpirationDays * 24 * 60 * 60 * 1000; // 365 days in milliseconds
        Date expirationDate = new Date(new Date().getTime() + oneYearInMillis);

        return Jwts.builder()
                .setSubject(userName)
                .setIssuedAt(new Date())
                .setExpiration(expirationDate)
                .claim("tokenType", "REFRESH")
                .signWith(the_key())
                .compact();
    }


    public String generateAccessToken(Authentication authentication) {
        String userName = authentication.getName();

        Date currentDate = new Date();
        Date expirationDate = new Date(currentDate.getTime() + accessTokenExpirationMillis);

        return Jwts.builder()
                .setSubject(userName)
                .setIssuedAt(currentDate)
                .setExpiration(expirationDate)
                .signWith(the_key())
                .claim("tokenType", "ACCESS")
                .compact();
    }


    /**
     * Generate FileBox API token with 1-year expiration
     * Database metadata controls actual expiration via sliding window
     */
    public String generateFileBoxApiToken(UUID tenantId,
                                          UUID userId,
                                          List<String> permissions,
                                          String apiKeyName,
                                          String environment) {
        Date currentDate = new Date();

        // Set JWT expiration to 1 year (long-lived)
        long oneYearInMillis = 365L * 24 * 60 * 60 * 1000;
        Date expirationDate = new Date(currentDate.getTime() + oneYearInMillis);

        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .setIssuedAt(currentDate)
                .setExpiration(expirationDate)
                .setIssuer("glueauth")
                .setAudience("filebox")
                .claim("tokenType", "API_KEY")
                .claim("tenantId", tenantId)
                .claim("userId", userId)
                .claim("permissions", permissions)
                .claim("apiKeyName", apiKeyName)
                .claim("environment", environment)
                .claim("scope", "filebox")
                .signWith(the_key())
                .compact();
    }

    /**
     * Validate FileBox API token using database metadata for expiration
     */
    public boolean validFileBoxApiToken(String token) throws Exception {
        try {
            // First validate JWT structure and signature
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(the_key())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            // Validate token type
            String tokenType = claims.get("tokenType", String.class);
            if (!"API_KEY".equals(tokenType)) {
                throw new TokenInvalidException("Not an API key token");
            }

            // Validate scope and audience
            if (!"filebox".equals(claims.get("scope", String.class))) {
                throw new TokenInvalidException("Invalid scope");
            }
            if (!"filebox".equals(claims.getAudience())) {
                throw new TokenInvalidException("Invalid audience");
            }

            // Check a database for token status and handle a sliding window
            return apiKeyService.validateAndUpdateToken(token);

        } catch (MalformedJwtException e) {
            throw new TokenInvalidException("Invalid token format");
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException("Token expired");
        } catch (UnsupportedJwtException e) {
            throw new TokenUnsupportedException("Unsupported token");
        } catch (IllegalArgumentException e) {
            throw new TokenEmptyException("Empty token");
        } catch (SignatureException e) {
            throw new TokenInvalidSignatureException("Invalid signature");
        }
    }

    public String getUserName(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(the_key())
                .build()
                .parseClaimsJws(token)
                .getBody();
        return claims.getSubject();
    }

    public boolean validToken(String token, String expectedTokenType) throws Exception {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(the_key())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String tokenType = claims.get("tokenType", String.class);
            return expectedTokenType.equals(tokenType);
        } catch (MalformedJwtException e) {
            throw new TokenInvalidException("Invalid token");
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException("Token expired");
        } catch (UnsupportedJwtException e) {
            throw new TokenUnsupportedException("Unsupported token");
        } catch (IllegalArgumentException e) {
            throw new TokenEmptyException("Empty token");
        } catch (SignatureException e) {
            throw new TokenInvalidSignatureException("Invalid signature");
        }
    }


    private Key the_key() {
        return Keys.hmacShaKeyFor(
                Decoders.BASE64.decode(secret_key)
        );
    }
}
