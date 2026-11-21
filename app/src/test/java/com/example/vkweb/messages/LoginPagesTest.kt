package com.example.vkweb.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginPagesTest {

    @Test fun `страницы входа`() {
        assertTrue(LoginPages.isLogin("https://id.vk.ru/auth?app_id=1&redirect_uri=x"))
        assertTrue(LoginPages.isLogin("https://m.vk.ru/login?to=bWFpbA"))
        assertTrue(LoginPages.isLogin("https://login.vk.com/?act=web_token"))
        assertTrue(LoginPages.isLogin("https://m.vk.com/join"))
    }

    @Test fun `обычные страницы и чужие сайты`() {
        assertFalse(LoginPages.isLogin("https://m.vk.ru/mail"))
        assertFalse(LoginPages.isLogin("https://m.vk.ru/mail/convo/100"))
        assertFalse(LoginPages.isLogin("https://m.vk.ru/authors"))
        assertFalse(LoginPages.isLogin("https://evil.example/auth"))
        assertFalse(LoginPages.isLogin(null))
        assertFalse(LoginPages.isLogin("не адрес"))
    }

    @Test fun `после входа — страница VK, но не вход (4_19)`() {
        org.junit.Assert.assertTrue(LoginPages.isAfterLogin("https://m.vk.com/feed"))
        org.junit.Assert.assertTrue(LoginPages.isAfterLogin("https://m.vk.ru/mail"))
        org.junit.Assert.assertFalse(LoginPages.isAfterLogin("https://id.vk.ru/auth?x=1"))
        org.junit.Assert.assertFalse(LoginPages.isAfterLogin("https://login.vk.com/?act=connect"))
        org.junit.Assert.assertFalse(LoginPages.isAfterLogin("https://m.vk.com/login"))
        org.junit.Assert.assertFalse(LoginPages.isAfterLogin("https://evilvk.com/feed"))
        org.junit.Assert.assertFalse(LoginPages.isAfterLogin(null))
    }
}
