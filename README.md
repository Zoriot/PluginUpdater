![This project proudly suppports the LGBTQIA+ community](https://raw.githubusercontent.com/OakLoaf/OakLoaf/refs/heads/main/assets/pride.png)

# PluginUpdater
**Please note that VelocityMC support requires https://github.com/PaperMC/Velocity/pull/1736**

PluginUpdater is a simple plugin and shadeable API that handles checking versions and automatically downloading plugin files from Modrinth, Spigot, Hangar and GitHub Releases. All updates and checks are ran asynchronously at a rate of 1 check/download per second to respect rate limits.

By default, PluginUpdater comes with support for 50+ commonly used plugins, you can easily configure any other plugins you use that are hosted on supported sites.

## Commonly Asked Questions
**What if there is a major update to a plugin?**
- Plugins with major version updates _(according to [semver](https://semver.org/))_ will appear red in `/updates` and will not be updated when running `/updater update all`, this is to encourage server admins to take time to read changelogs. You can bypass this by either running `/updater update all --force` or `/updater update <plugin>`

**X plugin broke when I updated!**
- By default, PluginUpdater only downloads plugins to the update folder and **does not** modify any plugin jars. If you have an issue with an update, report this to the developer of the plugin with the issue!

**Can CLI or Velocity replace old plugin jars directly?**
- Start Java with `-Dpluginupdater.replace-in-place=true` before `-jar` to enable this for CLI or Velocity, including the Velocity shadeable API. The new jar keeps the release's filename and is placed in the same folder as the original plugin jar. The original is deleted only after the download succeeds and the jar has been validated. If the release has the same filename, the original is atomically replaced.
- This option is disabled by default and is rejected by the running Paper plugin/API. CLI can update Paper jars offline; stop the server before doing so. This does not reload plugins: restart the server/proxy to load the update.
- An existing different jar with the release filename will not be overwritten. Locked jars (particularly on Windows) or unsupported atomic replacement cause an explicit failure. If deleting the old jar fails, PluginUpdater attempts to remove the new jar and reports any cleanup failure.

**Is there an API?**
- Adding PluginUpdater compatibility to your plugin is super easy and does not require an API. You can follow [these instructions](https://github.com/OakLoaf/PluginUpdater/wiki/PluginUpdater-Plugin#adding-support-for-pluginupdater) to make your plugin compatible!
- You can use our shadeable API to easily handle version checking/update downloading in your plugins. For more information about the API check out the [wiki page](https://github.com/OakLoaf/PluginUpdater/wiki/Shadeable-API)

<br>

## Getting support
If you need help setting up the plugin or have any questions, feel free to join the [LushPlugins discord server](https://discord.gg/mbPxvAxP3m)
