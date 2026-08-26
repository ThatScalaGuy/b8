/*
 * Copyright 2026 ThatScalaGuy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package b8

/** The single error type b8 raises when input cannot be decoded.
  *
  * Carries neither a stack trace nor suppressed exceptions: decode failures are
  * data, not crashes, and filling in a stack trace would dominate the cost of
  * the failure path. The backend's own exception, if any, is kept as `cause`.
  *
  * @param message
  *   what went wrong, in terms of the input
  * @param format
  *   name of the format that rejected the input, e.g. `"json"`
  * @param cause
  *   the backend exception this wraps, or `null`
  */
final class DecodeError(
    val message: String,
    val format: String,
    cause: Throwable = null
) extends RuntimeException(message, cause, false, false)
