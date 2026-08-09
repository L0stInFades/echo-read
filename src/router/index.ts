import { createRouter, createWebHashHistory } from 'vue-router'

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'shelf', component: () => import('../views/ShelfView.vue') },
    { path: '/read/:bookId', name: 'reader', component: () => import('../views/ReaderView.vue'), props: true },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})
