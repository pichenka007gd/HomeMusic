<#
.SYNOPSIS
  The private deployment release command is not used by the public self-host project.
.DESCRIPTION
  Build from android/ with the Gradle Wrapper and sign with your own key.
  This checkout deliberately never writes the private family update feed.
#>
[CmdletBinding()]
param()
throw 'This public checkout does not publish private update feeds. See android/README.md for Gradle builds and signing; users choose their server inside the app.'
