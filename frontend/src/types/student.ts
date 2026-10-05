export type DegreeType = 'ACADEMIC_MASTER' | 'PROFESSIONAL_MASTER'
export type BatchStatus = 'SCHEDULED' | 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'ARCHIVED' | 'CANCELLED'
export type StageCode = 'FILLING' | 'ROUND_1' | 'ROUND_2' | 'ROUND_3' | 'SUPPLEMENT'
export type StageStatus = 'NOT_STARTED' | 'WAITING_FILLING' | 'OPEN' | 'PROCESSING' | 'CLOSED'
export type PreferenceStatus = 'NOT_SUBMITTED' | 'SUBMITTED' | 'LOCKED' | 'WITHDRAWN'
export type MatchStatus = 'PENDING_ROUND_1' | 'PENDING_ROUND_2' | 'PENDING_ROUND_3' | 'MATCHED' | 'UNMATCHED'
export type CorrectionStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface StudentStageSummary {
  stageCode: StageCode
  stageStatus: StageStatus
  startAt: string | null
  endAt: string | null
}

export interface StudentBatchActions {
  canConfirmIdentity: boolean
  canSubmitPreferences: boolean
  canWithdrawPreferences: boolean
  canApplySupplement: boolean
}

export interface StudentBatchSummary {
  batchId: number
  batchName: string
  academicYearId: number
  academicYearName: string
  batchStatus: BatchStatus
  supplementPlanned: boolean
  currentStage: StudentStageSummary | null
  preferenceStatus: PreferenceStatus
  matchStatus: MatchStatus | null
  matchReason: string | null
  identityClassificationVersion: number | null
  confirmedClassificationVersion: number | null
  actions: StudentBatchActions
}

export interface StudentMajor {
  majorId: number
  majorCode: string
  majorName: string
}

export interface StudentResume {
  fileId: number
  fileName: string
  sizeBytes: number
  uploadedAt: string
  scanStatus: 'PENDING' | 'AVAILABLE' | 'REJECTED'
}

export interface StudentProfile {
  studentId: number
  studentNo: string
  fullName: string
  collegeId: number
  collegeName: string
  major: StudentMajor
  degreeType: DegreeType
  classificationVersion: number
  accountStatus: string
  profileVersion: number
  profileEtag: string
  biography: string
  contact: string
  resume: StudentResume | null
}

export interface MajorOption {
  majorId: number
  majorCode: string
  majorName: string
  active: boolean
}

export interface TeacherDirectoryItem {
  teacherId: number
  employeeNo: string
  displayName: string
  researchDirections: string[]
  profileSummary: string
  allowedDegreeTypes: DegreeType[]
  allowedMajors: StudentMajor[]
  canApply: boolean
}

export interface TeacherDetail extends TeacherDirectoryItem {
  biography: string
}

export interface PreferenceItem {
  teacherId: number
  preferenceOrder: number
  teacherName: string
  employeeNo: string
  researchDirections?: string[]
}

export interface StudentPreferences {
  preferenceStatus: PreferenceStatus
  submissionId: number | null
  versionNo: number | null
  submittedAt: string | null
  lockedAt: string | null
  items: PreferenceItem[]
}

export interface PreferenceSubmission {
  submissionId: number
  versionNo: number
  status: 'SUBMITTED' | 'WITHDRAWN' | 'LOCKED'
  submittedAt: string
  lockedAt: string | null
  items: PreferenceItem[]
}

export type StudentRoundState =
  | 'NO_PREFERENCE'
  | 'WAITING'
  | 'PENDING'
  | 'PROCESSED'
  | 'SKIPPED_BY_SCHEDULE'
  | 'ADMITTED'
  | 'NOT_ADMITTED'
  | 'CANCELLED_BY_BATCH'

export interface StudentRoundProgress {
  roundNo: 1 | 2 | 3
  preferenceOrder: 1 | 2 | 3
  teacherId: number | null
  teacherName: string | null
  state: StudentRoundState
  resultPublished: boolean
  processedAt: string | null
}

export interface StudentProgress {
  batchStatus: BatchStatus
  currentStage: StudentStageSummary | null
  preferenceStatus: PreferenceStatus
  matchStatus: MatchStatus | null
  matchReason: string | null
  rounds: StudentRoundProgress[]
  currentRelation: {
    teacherId: number
    teacherName: string
    source: 'ROUND_1' | 'ROUND_2' | 'ROUND_3' | 'SUPPLEMENT'
    lockedAt: string
  } | null
}

export type SupplementStatus = 'IN_REVIEW' | 'ADMITTED' | 'REJECTED' | 'CANCELLED_BY_BATCH'

export interface SupplementApplication {
  applicationId: number
  teacherId: number
  teacherName: string
  submittedAt: string
  status: SupplementStatus
  processedAt: string | null
}

export interface IdentityCorrectionRequest {
  requestId: number
  submittedAt: string
  currentClassificationVersion: number
  requestedMajor: StudentMajor | null
  requestedDegreeType: DegreeType | null
  studentExplanation: string
  status: CorrectionStatus
  handledAt: string | null
  handlingComment: string | null
  resultingClassificationVersion: number | null
}

export interface StudentNotice {
  noticeId: number
  title: string
  content: string
  sentAt: string
  readAt: string | null
}

