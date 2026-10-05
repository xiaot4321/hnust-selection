package cn.hnust.selection.entity;

/**
 * 人员管理列表使用的连接查询投影。
 *
 * <p>{@code id} 是学生或导师档案主键，{@code accountId} 与登录账号关联，{@code identifier} 对应学号或工号。
 * 学院和专业名称是列表展示所需的连接字段；此投影不读取密码、联系方式、简历或其他隐私资料。</p>
 */
public class PersonnelEntity {
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

    public PersonnelEntity(Long id, Long accountId, String loginIdentifier, String identifier,
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
