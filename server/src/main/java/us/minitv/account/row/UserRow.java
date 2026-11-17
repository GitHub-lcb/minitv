package us.minitv.account.row;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Row for table users. Generated from the Python models. */
@Data
public class UserRow {
    private Long id;
    private String email;
    private String displayName;
    private String avatarUrl;
    private String gender;
    private String bio;
    private LocalDate birthday;
    private String region;
    private String locale;
    private String publicId;
    private Integer followingCount;
    private Integer followerCount;
    private Integer receivedLikes;
    private Boolean reminderOptIn;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
