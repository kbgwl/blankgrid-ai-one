import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/brand.css'
import App from './App.vue'
import OrgChart from './views/OrgChart.vue'
import Dashboard from './views/Dashboard.vue'
import SettingsView from './views/SettingsView.vue'
import CommandCenter from './views/CommandCenter.vue'
import GoalDetail from './views/GoalDetail.vue'
import ArtifactLibrary from './views/ArtifactLibrary.vue'
import Approvals from './views/Approvals.vue'
import Meetings from './views/Meetings.vue'
import MeetingRoom from './views/MeetingRoom.vue'

const router = createRouter({
  history: createWebHashHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    { path: '/', name: 'command', component: CommandCenter },
    { path: '/goals/:id', name: 'goal-detail', component: GoalDetail },
    { path: '/org', name: 'org', component: OrgChart },
    { path: '/board', name: 'board', component: Dashboard },
    { path: '/meetings', name: 'meetings', component: Meetings },
    { path: '/meetings/:id', name: 'meeting-room', component: MeetingRoom },
    { path: '/artifacts', name: 'artifacts', component: ArtifactLibrary },
    { path: '/approvals', name: 'approvals', component: Approvals },
    { path: '/settings', name: 'settings', component: SettingsView },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

createApp(App).use(router).use(ElementPlus).mount('#app')
