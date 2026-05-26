package com.example.personareport.user.service;

import com.example.personareport.user.domain.UserAccount;
import com.example.personareport.user.dto.SignupRequest;
import com.example.personareport.user.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserAccount register(SignupRequest request) {
        String email = UserAccount.normalizeEmail(request.email());
        if (userAccountRepository.existsByEmail(email)) {
            throw new DuplicateEmailException("이미 가입된 이메일입니다. 로그인해 주세요.");
        }
        try {
            return userAccountRepository.save(UserAccount.create(
                    request.displayName().trim(),
                    email,
                    passwordEncoder.encode(request.password()),
                    Boolean.TRUE.equals(request.termsAccepted()),
                    Boolean.TRUE.equals(request.privacyAccepted()),
                    Boolean.TRUE.equals(request.marketingAccepted())
            ));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateEmailException("이미 가입된 이메일입니다. 로그인해 주세요.");
        }
    }

    @Transactional(readOnly = true)
    public UserAccount findByEmail(String email) {
        return userAccountRepository.findByEmail(UserAccount.normalizeEmail(email))
                .orElse(null);
    }

    public static class DuplicateEmailException extends RuntimeException {
        public DuplicateEmailException(String message) {
            super(message);
        }
    }
}
