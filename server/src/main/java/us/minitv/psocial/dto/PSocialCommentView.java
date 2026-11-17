package us.minitv.psocial.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * One comment joined with its author, as returned by the episode thread list.
 * Column names map from the {@code comments}/{@code users} select in
 * {@code psocial_comment.xml}.
 */
@Data
public class PSocialCommentView {
    private Long id;
    private Long userId;
    private String body;
    private String status;
    private LocalDateTime createdAt;
    private String publicId;
    private String displayName;
    private String avatarUrl;
}
