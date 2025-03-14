package com.djeno.backend.models.DTO.user;

import com.djeno.backend.models.enums.Role;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class UserForList {
    private Long id;
    private String username;
    private String email;
    private String name;
    private String surname;
    private String middleName;
    private Role role;
    private BigDecimal rating;
    private Boolean isBanned;
    private String banReason;
    private byte[] profilePicture;
    private String pictureMimeType; // MIME-тип
}
