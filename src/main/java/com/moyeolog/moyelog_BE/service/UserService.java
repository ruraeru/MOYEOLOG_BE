package com.moyeolog.moyelog_BE.service;

import com.moyeolog.moyelog_BE.dto.UserUpdateRequest;
import com.moyeolog.moyelog_BE.entity.User;
import com.moyeolog.moyelog_BE.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final FileService fileService;

    @Transactional(readOnly = true)
    public User getUser(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @Transactional
    public User updateUser(UUID id, UserUpdateRequest request, MultipartFile image) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (request.getNickname() != null && !request.getNickname().trim().isEmpty()) {
            user.setNickname(request.getNickname());
        }

        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }

        if (image != null && !image.isEmpty()) {
            String fileName = fileService.storeFile(image);
            user.setProfileImage("/uploads/" + fileName);
        }

        return userRepository.save(user);
    }
}
