package pinomaker.mbti.service.impl;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import pinomaker.mbti.common.dto.RequestResponseDto;
import pinomaker.mbti.common.dto.UserAuthority;
import pinomaker.mbti.domain.RefreshToken;
import pinomaker.mbti.domain.User;
import pinomaker.mbti.dto.RequestLoginUserDto;
import pinomaker.mbti.dto.RequestSaveUserDto;
import pinomaker.mbti.dto.RequestTokenDto;
import pinomaker.mbti.jwt.TokenDto;
import pinomaker.mbti.jwt.TokenProvider;
import pinomaker.mbti.repository.RefreshTokenRepository;
import pinomaker.mbti.repository.UserJpaRepository;
import pinomaker.mbti.service.UserService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserJpaRepository userJpaRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthenticationManagerBuilder authenticationManagerBuilder;
    private final TokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);

    @Override
    public RequestResponseDto<?> save(RequestSaveUserDto dto) {
        try {
            Optional<User> findUser = userJpaRepository.findUserById(dto.getId());
            if (findUser.isPresent()) {
                return RequestResponseDto.of(HttpStatus.BAD_REQUEST, RequestResponseDto.Code.FAILED, "이미 존재 하는 계정 입니다.", false);
            }

            User saveUser = User.builder()
                    .id(dto.getId())
                    .password(passwordEncoder.encode(dto.getPassword()))
                    .mbti(dto.getMbti())
                    .name(dto.getName())
                    .authority(UserAuthority.ROLE_USER)
                    .build();

            return RequestResponseDto.of(HttpStatus.OK, RequestResponseDto.Code.SUCCESS, "회원가입 성공", userJpaRepository.save(saveUser));
        } catch (Exception e) {
            logger.info("ERROR :" + e);
            return RequestResponseDto.of(HttpStatus.INTERNAL_SERVER_ERROR, RequestResponseDto.Code.FAILED, e.getMessage(), null);
        }
    }

    @Override
    public RequestResponseDto<?> login(RequestLoginUserDto dto) {
        try {
            Optional<User> findUser = userJpaRepository.findUserById(dto.getId());

            if (findUser.isEmpty()) {
                return RequestResponseDto.of(HttpStatus.BAD_REQUEST, RequestResponseDto.Code.FAILED, "사용자를 찾을 수 없습니다.", false);
            }

            if (!passwordEncoder.matches(dto.getPassword(), findUser.get().getPassword())) {
                return RequestResponseDto.of(HttpStatus.BAD_REQUEST, RequestResponseDto.Code.FAILED, "비밀번호가 같지 앖습니다.", false);
            }
            UsernamePasswordAuthenticationToken authenticationToken = dto.toAuthentication();
            Authentication authentication = authenticationManagerBuilder.getObject().authenticate(authenticationToken);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            Optional<RefreshToken> refreshToken = refreshTokenRepository.findByAuthKeyAndType(authentication.getName(), "user");

            TokenDto tokenDto = tokenProvider.generateTokenDto(authentication);

            if (refreshToken.isPresent()) {
                refreshTokenRepository.save(refreshToken.get().updateValue(tokenDto.getRefreshToken()));
            } else {
                refreshTokenRepository.save(RefreshToken.refreshTokenBuilder()
                        .authKey(authentication.getName())
                        .authValue(tokenDto.getRefreshToken())
                        .type("user")
                        .build());
            }

            Map<String, String> response = new HashMap<>();

            response.put("accessToken", tokenDto.getAccessToken());
            response.put("refreshToken", tokenDto.getRefreshToken());
            response.put("name", findUser.get().getName());
            response.put("mbti", findUser.get().getMbti());
            return RequestResponseDto.of(HttpStatus.OK, RequestResponseDto.Code.SUCCESS, "로그인 성공 하였습니다.", response);
        } catch (Exception e) {
            logger.info("ERROR :" + e);
            return RequestResponseDto.of(HttpStatus.INTERNAL_SERVER_ERROR, RequestResponseDto.Code.FAILED, e.getMessage(), null);
        }
    }

    public RequestResponseDto<?> getTokenByRefreshToken(RequestTokenDto tokenRequestDto) {
        if (!tokenProvider.validateToken(tokenRequestDto.getRefreshToken())) {
            return RequestResponseDto.of(HttpStatus.BAD_REQUEST, RequestResponseDto.Code.FAILED, "만료된 RefreshToken 입니다.", false);
        }

        Authentication authentication = tokenProvider.getAuthentication(tokenRequestDto.getAccessToken());

        Optional<RefreshToken> refreshToken = refreshTokenRepository.findByAuthKeyAndType(authentication.getName(), "user");

        if (refreshToken.isEmpty()) {
            return RequestResponseDto.of(HttpStatus.BAD_REQUEST, RequestResponseDto.Code.FAILED, "로그아웃 된 사용자 입니다.", false);
        }

        if (!refreshToken.get().getAuthValue().equals(tokenRequestDto.getRefreshToken())) {
            return RequestResponseDto.of(HttpStatus.BAD_REQUEST, RequestResponseDto.Code.FAILED, "토큰의 유저 정보가 일치하지 않습니다.", false);
        }

        if (!tokenProvider.validateToken(tokenRequestDto.getRefreshToken())) {
            return RequestResponseDto.of(HttpStatus.BAD_REQUEST, RequestResponseDto.Code.FAILED, "만료된 RefreshToken 입니다.", false);
        }

        TokenDto tokenDto = tokenProvider.generateTokenDto(authentication);

        refreshTokenRepository.save(refreshToken.get().updateValue(tokenDto.getRefreshToken()));

        return RequestResponseDto.of(HttpStatus.OK, RequestResponseDto.Code.SUCCESS, "토큰 재발급에 성공 하였습니다.", tokenDto);
    }
}
