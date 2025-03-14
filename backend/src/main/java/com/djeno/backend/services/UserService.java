package com.djeno.backend.services;

import com.djeno.backend.exceptions.EmailAlreadyExistsException;
import com.djeno.backend.exceptions.UsernameAlreadyExistsException;
import com.djeno.backend.models.DTO.user.*;
import com.djeno.backend.models.enums.Role;
import com.djeno.backend.models.models.Skill;
import com.djeno.backend.models.models.User;
import com.djeno.backend.models.models.UserSkill;
import com.djeno.backend.repositories.SkillRepository;
import com.djeno.backend.repositories.UserRepository;
import com.djeno.backend.repositories.UserSkillRepository;
import lombok.RequiredArgsConstructor;
import org.apache.tika.Tika;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class UserService {

    private final UserRepository userRepository;
    private final SkillRepository skillRepository;
    private final UserSkillRepository userSkillRepository;

    private final MinioService minioService;
    private final ApplicationContext applicationContext;
    Tika tika = new Tika();


    // Получение баланса
    public BigDecimal getBalance() {
        return getCurrentUser().getBalance();
    }

    // Пополнение баланса
    public BigDecimal deposit(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than 0");
        }

        User user = getCurrentUser();
        user.setBalance(user.getBalance().add(amount));
        userRepository.save(user);
        return user.getBalance();
    }

    // Вывод денег с баланса
    public BigDecimal withdraw(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than 0");
        }

        User user = getCurrentUser();
        if (user.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds");
        }

        user.setBalance(user.getBalance().subtract(amount));
        userRepository.save(user);
        return user.getBalance();
    }

    public UserHeaderinfo getUserHeaderinfo() {
        User currentUser = getCurrentUser();
        UserHeaderinfo userHeaderinfo = new UserHeaderinfo();
        userHeaderinfo.setUsername(currentUser.getUsername());
        userHeaderinfo.setRole(currentUser.getRole().toString());
        userHeaderinfo.setBalance(currentUser.getBalance());

        // Загрузка картинки, если она есть
        if (currentUser.getProfilePictureUrl() != null && !currentUser.getProfilePictureUrl().isEmpty()) {
            try {
                InputStream inputStream = minioService.downloadFile(currentUser.getProfilePictureUrl(), MinioService.AVATARS_BUCKET);
                byte[] profilePicture = inputStream.readAllBytes();
                // Определение MIME-типа
                String mimeType = tika.detect(profilePicture);
                userHeaderinfo.setProfilePicture(profilePicture);
                userHeaderinfo.setPictureMimeType(mimeType);
            } catch (IOException e) {
                // Обработка ошибки, если не удалось загрузить картинку
                e.printStackTrace();
            }
        }

        return userHeaderinfo;
    }

    /**
     * Создать новый навык
     */
    public Skill createSkill(String skillName) {
        User currentUser = getCurrentUser();

        if (currentUser.getRole() != Role.ROLE_ADMIN && currentUser.getRole() != Role.ROLE_MAIN_ADMIN) {
            throw new RuntimeException("Недостаточно прав для создания навыка");
        }

        if (skillRepository.findByName(skillName).isPresent()) {
            throw new RuntimeException("Навык с таким именем уже существует");
        }

        Skill skill = new Skill();
        skill.setName(skillName);
        return skillRepository.save(skill);
    }

    /**
     * Получить все навыки
     */
    public List<Skill> getAllSkills() {
        return skillRepository.findAll();
    }

    /**
     * Получить навыки пользователя
     */
    public List<Skill> getUserSkills() {
        User currentUser = getCurrentUser();
        return userSkillRepository.findByUser(currentUser).stream()
                .map(UserSkill::getSkill)
                .collect(Collectors.toList());
    }

    /**
     * Добавить навык пользователю
     */
    public void addSkillToUser(Long skillId) {
        User currentUser = getCurrentUser();
        if (currentUser.getRole() != Role.ROLE_FREELANCER) {
            throw new RuntimeException("Только пользователи с ролью ROLE_FREELANCER могут добавлять навыки");
        }

        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new RuntimeException("Навык не найден"));

        // Проверяем, есть ли уже такой навык у пользователя
        if (userSkillRepository.findByUserAndSkill(currentUser, skill).isPresent()) {
            throw new RuntimeException("Навык уже добавлен пользователю");
        }

        UserSkill userSkill = UserSkill.create(currentUser, skill);
        userSkillRepository.save(userSkill);
    }

    /**
     * Удалить навык у пользователя
     */
    public void removeSkillFromUser(Long skillId) {
        User currentUser = getCurrentUser();
        if (currentUser.getRole() != Role.ROLE_FREELANCER) {
            throw new RuntimeException("Только пользователи с ролью ROLE_FREELANCER могут удалять навыки");
        }

        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new RuntimeException("Навык не найден"));

        UserSkill userSkill = userSkillRepository.findByUserAndSkill(currentUser, skill)
                .orElseThrow(() -> new RuntimeException("Навык не найден у пользователя"));

        userSkillRepository.delete(userSkill);
    }

    public void changeUsername(String newUsername) {
        User currentUser = getCurrentUser();
        if (isUsernameExists(newUsername)) {
            throw new UsernameAlreadyExistsException("Имя пользователя уже занято");
        }
        currentUser.setUsername(newUsername);
        save(currentUser);
    }

    public void changeEmail(String newEmail) {
        User currentUser = getCurrentUser();
        if (isEmailExists(newEmail)) {
            throw new EmailAlreadyExistsException("Email уже занят");
        }
        currentUser.setEmail(newEmail);
        currentUser.setIsEmailVerified(false);
        save(currentUser);
    }

    public void updateProfilePicture(MultipartFile file) {
        User currentUser = getCurrentUser();

        String uniqueFileName = minioService.uploadFile(file, MinioService.AVATARS_BUCKET);

        if (currentUser.getProfilePictureUrl() != null) {
            minioService.deleteFile(currentUser.getProfilePictureUrl(), MinioService.AVATARS_BUCKET);
        }

        currentUser.setProfilePictureUrl(uniqueFileName);
        userRepository.save(currentUser);
    }

    /**
     * Получение публичной информации о пользователе по username
     *
     * @param username имя пользователя
     * @return информация о пользователе
     */
    public UserProfileInfoPublic getPublicProfileInfoByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден"));

        List<Skill> skills = new ArrayList<>();
        if (user.getRole() == Role.ROLE_FREELANCER) {
             skills = userSkillRepository.findByUser(user)
                    .stream()
                    .map(UserSkill::getSkill)
                    .collect(Collectors.toList());
        }
        UserProfileInfoPublic userProfileInfo = UserProfileInfoPublic.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .name(user.getName())
                .surname(user.getSurname())
                .middleName(user.getMiddleName())
                .role(user.getRole())
                .description(user.getDescription())
                .createdAt(user.getCreatedAt())
                .rating(user.getRating())
                .isBanned(user.getIsBanned())
                .banReason(user.getBanReason())
                .skills(skills)
                .build();

        if (user.getProfilePictureUrl() != null) {
            try (InputStream inputStream = minioService.downloadFile(user.getProfilePictureUrl(), MinioService.AVATARS_BUCKET)) {
                byte[] picture = inputStream.readAllBytes();
                String pictureMimeType = tika.detect(picture); // Определяем MIME-тип
                userProfileInfo.setProfilePicture(picture);
                userProfileInfo.setPictureMimeType(pictureMimeType);
            } catch (IOException e) {
                throw new RuntimeException("Ошибка при загрузке картинки профиля", e);
            }
        }

        return userProfileInfo;
    }

    public UserProfileInfo getFullProfileInfo() {
        User currentUser = getCurrentUser();

        UserProfileInfo userProfileInfo = UserProfileInfo.builder()
                .id(currentUser.getId())
                .username(currentUser.getUsername())
                .email(currentUser.getEmail())
                .name(currentUser.getName())
                .surname(currentUser.getSurname())
                .middleName(currentUser.getMiddleName())
                .role(currentUser.getRole())
                .description(currentUser.getDescription())
                .createdAt(currentUser.getCreatedAt())
                .rating(currentUser.getRating())
                .isEmailVerified(currentUser.getIsEmailVerified())
                .twoFactorEnabled(currentUser.getTwoFactorEnabled())
                .isBanned(currentUser.getIsBanned())
                .banReason(currentUser.getBanReason())
                .build();

        if (currentUser.getProfilePictureUrl() != null) {
            try (InputStream inputStream = minioService.downloadFile(currentUser.getProfilePictureUrl(), MinioService.AVATARS_BUCKET)) {
                byte[] picture = inputStream.readAllBytes();
                String pictureMimeType = tika.detect(picture); // Определяем MIME-тип
                userProfileInfo.setProfilePicture(picture);
                userProfileInfo.setPictureMimeType(pictureMimeType);
            } catch (IOException e) {
                throw new RuntimeException("Ошибка при загрузке картинки профиля", e);
            }
        }

        return userProfileInfo;
    }

    public void changePassword(ChangePasswordRequest changePasswordRequest) {
        User currentUser = getCurrentUser();
        PasswordEncoder passwordEncoder = applicationContext.getBean(PasswordEncoder.class);
        if (!passwordEncoder.matches(changePasswordRequest.getOldPassword(), currentUser.getPassword())) {
            throw new RuntimeException("Старый пароль неверный");
        }

        String newPasswordHash = passwordEncoder.encode(changePasswordRequest.getNewPassword());
        currentUser.setPassword(newPasswordHash);
        userRepository.save(currentUser);
    }

    /**
     * Метод для выдачи бана пользователю
     *
     * @param userId ID пользователя
     * @param reason Причина бана
     * @return
     */
    public User banUser(Long userId, String reason) {
        User currentUser = getCurrentUser();

        if (!isAdminOrMainAdmin(currentUser)) {
            throw new RuntimeException("Недостаточно прав для бана пользователя");
        }

        User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("Пользователь не найден"));

        if (isAdminOrMainAdmin(user)) {
            throw new RuntimeException("Невозможно забанить пользователя с ролью ADMIN или MAIN_ADMIN");
        }

        if (user.getIsBanned()) {
            throw new RuntimeException("Пользователь уже забанен по причине: '" + user.getBanReason() + "'");
        }

        user.setIsBanned(true);
        user.setBanReason(reason);

        return userRepository.save(user);
    }

    /**
     * Метод для снятия бана у пользователя
     *
     * @param userId ID пользователя
     * @return
     */
    public User unbanUser(Long userId) {
        User currentUser = getCurrentUser();

        if (!isAdminOrMainAdmin(currentUser)) {
            throw new RuntimeException("Недостаточно прав для разбана пользователя");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден"));

        if (!user.getIsBanned()) {
            throw new RuntimeException("Пользователь не находится в бане");
        }

        user.setIsBanned(false);
        user.setBanReason(null);

        return userRepository.save(user);
    }

    /**
     * Метод нужен для проверки является ли пользователем админом
     * @param user
     * @return
     */
    private boolean isAdminOrMainAdmin(User user) {
        Role userRole = user.getRole();
        return userRole == Role.ROLE_ADMIN || userRole == Role.ROLE_MAIN_ADMIN;
    }

    /**
     * Метод нужен для проверки есть ли у пользователя права главного администратора
     * @param user
     * @return
     */
    private boolean isMainAdmin(User user) {
        return user.getRole() == Role.ROLE_MAIN_ADMIN;
    }

    /**
     * Метод нужен для проверки есть ли у пользователя права обычного администратора
     * @param user
     * @return
     */
    private boolean isAdmin(User user) {
        return user.getRole() == Role.ROLE_ADMIN;
    }

    /**
     * Метод для выдачи пользователю прав обычного администратора
     * Данный функционал может использовать только пользователь с ролью ROLE_MAIN_ADMIN
     * @param userId Id пользователя, кому выдаем права
     * @return
     */
    public User grantAdminRole(Long userId) {
        User currentUser = getCurrentUser();

        if(!isMainAdmin(currentUser)) {
            throw new RuntimeException("Недостаточно прав для выдачи роли");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден"));

        // Если у пользователя уже есть роль ADMIN, то не меняем роль
        if (isAdmin(user)) {
            throw new RuntimeException("Пользователь уже имеет роль ADMIN");
        }

        user.setRole(Role.ROLE_ADMIN);
        return userRepository.save(user);
    }

    /**
     * Метод для снятия у пользователя прав обычного администратора
     * Данный функционал может использовать только пользователь с ролью ROLE_MAIN_ADMIN
     * @param userId Id пользователя, с кого снимаем права
     * @return
     */
    public User revokeAdminRole(Long userId) {
        User currentUser = getCurrentUser();

        // Проверяем, может ли текущий пользователь снять роль
        if (!isMainAdmin(currentUser)) {
            throw new RuntimeException("Недостаточно прав для снятия роли");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден"));

        if (!isAdmin(user)) {
            throw new RuntimeException("Пользователь не имеет роли ADMIN");
        }

        user.setRole(Role.ROLE_CUSTOMER); // Возвращаем роль на базовую (сделать так, чтобы можно выло выбрать какую)
        return userRepository.save(user);
    }

    /**
     * Метод для выдачи пользователю прав главного администратора
     * Данный функционал может использовать только пользователь с ролью ROLE_MAIN_ADMIN
     * @param userId Id пользователя, кому выдаем права
     * @return
     */
    public User grantMainAdminRole(Long userId) {
        User currentUser = getCurrentUser();

        if (!isMainAdmin(currentUser)) {
            throw new RuntimeException("Недостаточно прав для выдачи роли");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден"));

        if (user.getRole() == Role.ROLE_MAIN_ADMIN) {
            throw new RuntimeException("Пользователь уже имеет роль MAIN_ADMIN");
        }

        user.setRole(Role.ROLE_MAIN_ADMIN);
        return userRepository.save(user);
    }

    /**
     * Вспомогательный метод для проверки строки на пустоту или null
     *
     * @param str строка для проверки
     * @return true, если строка null или пуста
     */
    private boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    /**
     * Метод изменяет пользовательские данные такие как:
     * name, middleName, surname, description
     * @param userDetailsUpdate
     * @return
     */
    public User updateUserDetails(UserDetailsUpdate userDetailsUpdate) {
        User currentUser = getCurrentUser();

        currentUser.setName(isEmpty(userDetailsUpdate.getName()) ? null : userDetailsUpdate.getName());
        currentUser.setSurname(isEmpty(userDetailsUpdate.getSurname()) ? null : userDetailsUpdate.getSurname());
        currentUser.setMiddleName(isEmpty(userDetailsUpdate.getMiddleName()) ? null : userDetailsUpdate.getMiddleName());
        currentUser.setDescription(isEmpty(userDetailsUpdate.getDescription()) ? null : userDetailsUpdate.getDescription());

        return save(currentUser);
    }

    /**
     * Метод для проверки существования пользователя с данным username
     * @param username
     * @return true - пользователь существует, false - не существует
     */
    public boolean isUsernameExists(String username) {
        return userRepository.existsByUsername(username);
    }

    /**
     * Метод для проверки существования пользователя с данным email
     * @param email
     * @return true - пользователь существует, false - не существует
     */
    public boolean isEmailExists(String email) {
        return userRepository.existsByEmail(email);
    }

    /**
     * Сохранение пользователя
     *
     * @return сохраненный пользователь
     */
    public User save(User user) {
        return userRepository.save(user);
    }

    /**
     * Создание пользователя
     *
     * @return созданный пользователь
     */
    public User create(User user) {
        if (isUsernameExists(user.getUsername())) {

            throw new UsernameAlreadyExistsException("User with that username already exists");
        }

        if (isEmailExists(user.getEmail())) {
            throw new EmailAlreadyExistsException("User with this email already exists");
        }

        return save(user);
    }

    /**
     * Получение пользователя по username пользователя
     *
     * @return пользователь
     */
    public User getByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден"));
    }

    /**
     * Получение пользователя по email пользователя
     *
     * @return пользователь
     */
    public User getByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден"));
    }

    /**
     * Получение пользователя по username или email пользователя
     *
     * @return пользователь
     */
    public User getByUsernameOrEmail(String identifier) {
        return userRepository.findByUsername(identifier)
                .orElseGet(() -> userRepository.findByEmail(identifier)
                .orElseThrow(() -> new RuntimeException("Пользователь не найден")));
    }

    /**
     * Получение пользователя по имени пользователя
     * <p>
     * Нужен для Spring Security
     *
     * @return пользователь
     */
    public UserDetailsService userDetailsService() {
        return this::getByUsernameOrEmail;
    }

    /**
     * Получение текущего пользователя
     *
     * @return текущий пользователь
     */
    public User getCurrentUser() {
        // Получение имени пользователя из контекста Spring Security
        var username = SecurityContextHolder.getContext().getAuthentication().getName();
        return getByUsername(username);
    }
}
