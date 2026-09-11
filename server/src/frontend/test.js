import { useDark, useToggle } from '@vueuse/core'

const isDark = useDark({
  storageKey: 'taotao_admin_theme',
  valueDark: 'dark',
  valueLight: 'light' // 撤回刚才的修改，VueUse 实际上是通过 setAttribute('class', value) 来执行的
});
const toggleTheme = useToggle(isDark);
