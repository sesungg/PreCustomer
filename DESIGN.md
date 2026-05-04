---
version: "alpha"
name: "CustomerPreview"
description: "미리고객 / CustomerPreview design system for an AI-based virtual customer reaction report MVP."
colors:
  primary: "#111827"
  secondary: "#374151"
  tertiary: "#2563EB"
  tertiaryHover: "#1D4ED8"
  accent: "#0F766E"
  background: "#FFFFFF"
  surface: "#F9FAFB"
  surfaceAlt: "#F3F4F6"
  border: "#E5E7EB"
  muted: "#6B7280"
  danger: "#B91C1C"
  warning: "#B45309"
  success: "#047857"
  onPrimary: "#FFFFFF"
  onTertiary: "#FFFFFF"
typography:
  h1:
    fontFamily: "Pretendard, Inter, system-ui, -apple-system, BlinkMacSystemFont, sans-serif"
    fontSize: "2.5rem"
    fontWeight: 800
    lineHeight: 1.2
    letterSpacing: "-0.03em"
  h2:
    fontFamily: "Pretendard, Inter, system-ui, -apple-system, BlinkMacSystemFont, sans-serif"
    fontSize: "1.75rem"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "-0.02em"
  h3:
    fontFamily: "Pretendard, Inter, system-ui, -apple-system, BlinkMacSystemFont, sans-serif"
    fontSize: "1.25rem"
    fontWeight: 700
    lineHeight: 1.35
  body:
    fontFamily: "Pretendard, Inter, system-ui, -apple-system, BlinkMacSystemFont, sans-serif"
    fontSize: "1rem"
    fontWeight: 400
    lineHeight: 1.7
  bodySmall:
    fontFamily: "Pretendard, Inter, system-ui, -apple-system, BlinkMacSystemFont, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.6
  label:
    fontFamily: "Pretendard, Inter, system-ui, -apple-system, BlinkMacSystemFont, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 600
    lineHeight: 1.4
rounded:
  sm: "6px"
  md: "10px"
  lg: "16px"
  xl: "24px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "40px"
  xxl: "64px"
components:
  buttonPrimary:
    backgroundColor: "{colors.tertiary}"
    textColor: "{colors.onTertiary}"
    rounded: "{rounded.md}"
    padding: "12px 18px"
  buttonSecondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.primary}"
    rounded: "{rounded.md}"
    padding: "12px 18px"
  card:
    backgroundColor: "{colors.background}"
    textColor: "{colors.primary}"
    rounded: "{rounded.lg}"
    padding: "24px"
  reportCard:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.primary}"
    rounded: "{rounded.lg}"
    padding: "24px"
---

## Overview

CustomerPreview, also named 미리고객 in Korean, is a trustworthy and minimal AI report service.

The design should feel like a lightweight consulting report, not a playful AI toy.  
The product helps solo founders, side-project developers, smart store sellers, SaaS makers, course sellers, and contest participants preview virtual customer reactions before launching.

The UI must communicate:
- clarity
- trust
- practical usefulness
- affordability
- calm confidence

Avoid exaggerated startup visuals, neon gradients, emojis, overly playful illustrations, and aggressive sales language.

## Colors

Use a mostly white and slate-based palette.

- Primary text is deep slate/near black.
- Secondary text is gray and calm.
- Blue is used only for important CTAs and selected states.
- Teal can be used sparingly for positive insight or helpful recommendation.
- Red and amber should be used only for warnings, objections, or risk indicators.
- Backgrounds should remain bright, clean, and report-like.

Do not use many accent colors in one screen.

## Typography

Use Korean-friendly sans-serif typography.

Preferred stack:
Pretendard, Inter, system-ui, -apple-system, BlinkMacSystemFont, sans-serif.

Headings should be bold and slightly condensed with negative letter spacing.  
Body text should be highly readable with generous line height.

The report page should feel like a document that can be printed or saved as PDF.

## Layout

Use generous whitespace.

Landing page:
- max-width around 1080px to 1200px
- strong hero section
- clear value proposition
- simple explanation cards
- sample report preview section
- single CTA repeated naturally

Form page:
- centered layout
- max-width around 760px
- wide input fields
- clear helper text
- visible privacy warning

Admin page:
- simple table-first layout
- no complex dashboard
- status badges
- clear action buttons

Report page:
- document layout
- max-width around 900px
- section cards
- score cards near the top
- persona reaction cards or table
- print-friendly spacing

## Components

### Buttons

Primary buttons should be blue, solid, and simple.

Use primary buttons for:
- 리포트 신청하기
- 저장하기
- 리포트 생성
- 리포트 보기

Secondary buttons should be subtle with border or light gray background.

Avoid oversized, glossy, or gradient buttons.

### Cards

Cards should use:
- white or very light gray background
- subtle border
- soft rounded corners
- minimal shadow or no shadow

Cards should not feel like flashy marketing widgets.  
They should feel like organized consulting notes.

### Forms

Forms must be easy to scan.

Each field should have:
- clear label
- optional helper text
- visible validation message

Important warning:
Users must be told not to enter personal information, customer DB, revenue data, contracts, internal documents, or sensitive medical/financial/legal content.

### Status Badges

Use calm status badges:
- REQUESTED: gray
- PAID: blue
- GENERATING: amber
- COMPLETED: green
- FAILED: red

Badges should be small and readable.

### Report Scores

Score cards should be clear and restrained.

Example:
- 전체 관심도 72점
- 사용/구매 의향 61점

Do not use gamified visuals.  
A simple number, label, and short interpretation is enough.

## Do's and Don'ts

### Do

- Use Korean UI copy.
- Keep the UI minimal and trustworthy.
- Make report pages printable.
- Use clear hierarchy.
- Keep admin screens simple.
- Use practical report language.
- Show disclaimers clearly.
- Explain that reports are AI-based virtual customer simulations.

### Don't

- Do not use emojis.
- Do not claim this is real consumer research.
- Do not guarantee sales increase.
- Do not guarantee conversion improvement.
- Do not use exaggerated marketing claims.
- Do not overuse gradients.
- Do not add unnecessary charts.
- Do not create complex dashboards.
- Do not make the UI feel like a toy.

## Copy Tone

Use concise, business-like Korean.

Preferred phrases:
- AI 기반 가상고객 시뮬레이션
- 출시 전 사전 반응 진단
- 한국형 가상고객 반응 리포트
- 고객 반응을 미리 확인하세요
- 실제 고객 조사 전 참고용 리포트

Avoid:
- 100% 검증
- 매출 상승 보장
- 구매 전환 보장
- 실제 소비자 조사와 동일
- 무조건 팔리는 상품