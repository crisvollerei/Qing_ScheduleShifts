    // Apply the saved or time-based theme before the first paint.
    (() => {
      const hour = new Date().getHours();
      const timedTheme = hour >= 7 && hour < 19 ? 'light' : 'dark';
      try {
        const theme = localStorage.getItem('qingzheng-theme');
        const mode = localStorage.getItem('qingzheng-theme-mode');
        document.documentElement.dataset.theme = mode === 'auto' || !['light','dark'].includes(theme) ? timedTheme : theme;
      } catch (_) { document.documentElement.dataset.theme = timedTheme; }
    })();
