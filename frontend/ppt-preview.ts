import { createApp, h } from 'vue'
import StudentWorkspace from './src/components/student/StudentWorkspace.vue'
import TeacherWorkspace from './src/components/teacher/TeacherWorkspace.vue'
import BatchManagementPanel from './src/components/admin/BatchManagementPanel.vue'
import './src/style.css'

const mode = new URLSearchParams(location.search).get('view')
const studentUser = {
  accountId: 101,
  loginIdentifier: '202600101',
  role: 'STUDENT' as const,
  accountStatus: 'ACTIVE',
  mustChangePassword: false,
  identity: { id: 1001, displayName: '林同学', identifier: '202600101', collegeId: 1 },
  authorizations: [],
}

const root = mode === 'student'
  ? h(StudentWorkspace, { user: studentUser })
  : mode === 'teacher'
    ? h(TeacherWorkspace)
    : h(BatchManagementPanel, { canManage: true, canManagePersonnel: true })

createApp({ render: () => root }).mount('#app')
