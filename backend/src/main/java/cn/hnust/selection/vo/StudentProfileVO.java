package cn.hnust.selection.vo;

/** 学生本人资料的接口响应，不暴露数据库 Entity 或文件存储键。 */
public class StudentProfileVO {
    private Long studentId;
    private String studentNo;
    private String fullName;
    private Long collegeId;
    private String collegeName;
    private StudentMajorVO major;
    private String degreeType;
    private Integer classificationVersion;
    private String accountStatus;
    private Integer profileVersion;
    private String profileEtag;
    private String biography;
    private String contact;
    private StudentResumeVO resume;

    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }
    public String getStudentNo() { return studentNo; }
    public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public Long getCollegeId() { return collegeId; }
    public void setCollegeId(Long collegeId) { this.collegeId = collegeId; }
    public String getCollegeName() { return collegeName; }
    public void setCollegeName(String collegeName) { this.collegeName = collegeName; }
    public StudentMajorVO getMajor() { return major; }
    public void setMajor(StudentMajorVO major) { this.major = major; }
    public String getDegreeType() { return degreeType; }
    public void setDegreeType(String degreeType) { this.degreeType = degreeType; }
    public Integer getClassificationVersion() { return classificationVersion; }
    public void setClassificationVersion(Integer classificationVersion) { this.classificationVersion = classificationVersion; }
    public String getAccountStatus() { return accountStatus; }
    public void setAccountStatus(String accountStatus) { this.accountStatus = accountStatus; }
    public Integer getProfileVersion() { return profileVersion; }
    public void setProfileVersion(Integer profileVersion) { this.profileVersion = profileVersion; }
    public String getProfileEtag() { return profileEtag; }
    public void setProfileEtag(String profileEtag) { this.profileEtag = profileEtag; }
    public String getBiography() { return biography; }
    public void setBiography(String biography) { this.biography = biography; }
    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }
    public StudentResumeVO getResume() { return resume; }
    public void setResume(StudentResumeVO resume) { this.resume = resume; }

    public static class StudentMajorVO {
        private Long majorId;
        private String majorCode;
        private String majorName;

        public StudentMajorVO() { }
        public StudentMajorVO(Long majorId, String majorCode, String majorName) {
            this.majorId = majorId;
            this.majorCode = majorCode;
            this.majorName = majorName;
        }
        public Long getMajorId() { return majorId; }
        public void setMajorId(Long majorId) { this.majorId = majorId; }
        public String getMajorCode() { return majorCode; }
        public void setMajorCode(String majorCode) { this.majorCode = majorCode; }
        public String getMajorName() { return majorName; }
        public void setMajorName(String majorName) { this.majorName = majorName; }
    }

    public static class StudentResumeVO {
        private Long fileId;
        private String fileName;
        private Long sizeBytes;
        private String uploadedAt;
        private String scanStatus;

        public StudentResumeVO() { }
        public StudentResumeVO(Long fileId, String fileName, Long sizeBytes, String uploadedAt, String scanStatus) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.sizeBytes = sizeBytes;
            this.uploadedAt = uploadedAt;
            this.scanStatus = scanStatus;
        }
        public Long getFileId() { return fileId; }
        public void setFileId(Long fileId) { this.fileId = fileId; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public Long getSizeBytes() { return sizeBytes; }
        public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
        public String getUploadedAt() { return uploadedAt; }
        public void setUploadedAt(String uploadedAt) { this.uploadedAt = uploadedAt; }
        public String getScanStatus() { return scanStatus; }
        public void setScanStatus(String scanStatus) { this.scanStatus = scanStatus; }
    }
}
