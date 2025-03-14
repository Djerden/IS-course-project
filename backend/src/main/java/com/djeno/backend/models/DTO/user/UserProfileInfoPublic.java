package com.djeno.backend.models.DTO.user;

import com.djeno.backend.models.enums.Role;
import com.djeno.backend.models.models.Skill;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileInfoPublic {
    private Long id;
    private String username;
    private String email;
    private String name;
    private String surname;
    private String middleName;
    private Role role;
    private String description;
    private LocalDateTime createdAt;
    private BigDecimal rating;
    private Boolean isBanned;
    private String banReason;
    private byte[] profilePicture;
    private String pictureMimeType; // MIME-тип
    private List<Skill> skills; // Добавляем навыки
}
