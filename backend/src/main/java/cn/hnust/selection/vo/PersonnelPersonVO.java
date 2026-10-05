package cn.hnust.selection.vo;

/**
 * 学生和导师管理列表使用的安全身份视图。
 *
 * <p>{@code id} 是人员档案 ID，{@code accountId} 是登录账号 ID；{@code identifier} 为学号或工号，
 * {@code loginIdentifier} 为登录名。学院、专业、学位和入学年字段便于管理员核对档案，
 * {@code classificationVersion} 与 {@code profileReviewStatus} 分别表示分类修订版本和导师资料审核状态。</p>
 *
 * <p>该视图不返回密码、联系方式、简历或其他需要单独授权的敏感资料。</p>
 */
public class PersonnelPersonVO {
    private final Long id;
    private final Long accountId;
    private final String loginIdentifier;
    private final String identifier;
    private final String fullName;
    private final Long collegeId;
    private final String collegeName;
    private final String majorCode;
    private final String majorName;
    private final String degreeType;
    private final String enrollmentYearCode;
    private final Integer classificationVersion;
    private final String profileReviewStatus;

    public PersonnelPersonVO(Long id, Long accountId, String loginIdentifier, String identifier,
                                   String fullName, Long collegeId, String collegeName, String majorCode,
                                   String majorName, String degreeType, String enrollmentYearCode,
                                   Integer classificationVersion, String profileReviewStatus) {
        this.id = id;
        this.accountId = accountId;
        this.loginIdentifier = loginIdentifier;
        this.identifier = identifier;
        this.fullName = fullName;
        this.collegeId = collegeId;
        this.collegeName = collegeName;
        this.majorCode = majorCode;
        this.majorName = majorName;
        this.degreeType = degreeType;
        this.enrollmentYearCode = enrollmentYearCode;
        this.classificationVersion = classificationVersion;
        this.profileReviewStatus = profileReviewStatus;
    }
    public Long getId() {
        return id;
    }
    public Long getAccountId() {
        return accountId;
    }
    public String getLoginIdentifier() {
        return loginIdentifier;
    }
    public String getIdentifier() {
        return identifier;
    }
    public String getFullName() {
        return fullName;
    }
    public Long getCollegeId() {
        return collegeId;
    }
    public String getCollegeName() {
        return collegeName;
    }
    public String getMajorCode() {
        return majorCode;
    }
    public String getMajorName() {
        return majorName;
    }
    public String getDegreeType() {
        return degreeType;
    }
    public String getEnrollmentYearCode() {
        return enrollmentYearCode;
    }
    public Integer getClassificationVersion() {
        return classificationVersion;
    }
    public String getProfileReviewStatus() {
        return profileReviewStatus;
    }
}
